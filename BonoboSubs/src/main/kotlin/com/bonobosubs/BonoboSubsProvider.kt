package com.bonobosubs

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URLDecoder

class BonoboSubsProvider : MainAPI() {
    override var mainUrl = "https://bonobosubs.ovh"
    override var name = "BonoboSubs"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override var lang = "en"
    override val hasMainPage = true

    companion object {
        // Public Nextcloud share token: https://bonobosubs.ovh/s/download?dir=/4k
        private const val SHARE_TOKEN = "download"
        private const val PATH_4K = "/public.php/dav/files/$SHARE_TOKEN/4k/"
        private const val PATH_1080 = "/public.php/dav/files/$SHARE_TOKEN/1080p/"
        private const val PATH_SUBS = "/public.php/dav/files/$SHARE_TOKEN/Latest%20subtitle%20files/"

        // Plain-ASCII mirrors of one subtitle per episode, committed to this repo.
        private const val SUBS_RAW_BASE =
            "https://raw.githubusercontent.com/2Gbps/CloudStream-BonoboSubs/main/subs/"
        private const val BUNDLED_SUBS_MIN = 1
        private const val BUNDLED_SUBS_MAX = 157

        // Internal slugs, never fetched as http
        private const val SERIES_URL = "/renegade-immortal"
        private const val MOVIE_URL = "/battle-of-the-gods"

        private const val SERIES_TITLE = "Renegade Immortal"
        private const val MOVIE_TITLE = "Renegade Immortal: Battle of the Gods"

        private const val POSTER_URL =
            "https://media.kitsu.app/anime/48036/poster_image/large-b4d32359a87f74424144a75ffcfbdcae.jpeg"

        private const val SUBS_CACHE_MS = 10 * 60 * 1000L

        private val PROPFIND_BODY by lazy {
            """
            <?xml version="1.0"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop><d:resourcetype/><d:getcontentlength/></d:prop>
            </d:propfind>
            """.trimIndent().toRequestBody("application/xml".toMediaTypeOrNull())
        }

        // [BonoboSubs][4k]Renegade Immortal - 仙逆 Xian Ni - 001.mkv  (v3 batches)
        // [BonoboSubs][4k]Renegade Immortal - 仙逆 Xian Ni - 147.mkv  (current releases)
        // [BonoboSubs][4k]Renegade Immortal - Xian Ni Episode 077.mkv (older releases / full 1080p dir)
        private val EPISODE_PATTERNS = listOf(
            Regex("""Xian Ni - (\d{1,4})\.mkv$""", RegexOption.IGNORE_CASE),
            Regex("""Xian Ni Episode (\d{1,4})\.mkv$""", RegexOption.IGNORE_CASE)
        )

        // Fallback subtitle names on the live share (future episodes only)
        private val SUBTITLE_PATTERNS = listOf(
            Regex("""Xian Ni - (\d{1,4})\.ass$""", RegexOption.IGNORE_CASE),
            Regex("""Xian Ni Episode (\d{1,4})(?:_uncut)?(?:_bis)?\.ass$""", RegexOption.IGNORE_CASE)
        )

        private val MOVIE_PATTERN =
            Regex("""Renegade Immortal Movie.*\.mkv$""", RegexOption.IGNORE_CASE)
    }

    /*
     * Episode data MUST be the plain 4K URL. ShonenX builds its episode id as
     * "$url|$episodeNumber" and splits on '|' itself, so any '|' in the data
     * corrupts the URL reaching the player (mpv "Source error"). The 1080p
     * link is reconstructed from a cached WebDAV listing inside loadLinks.
     */

    private data class DavFile(val href: String, val fileName: String)

    private data class DavEntry(val href: String, val isCollection: Boolean, val fileName: String)

    private data class SubtitleEntry(val href: String, val episode: Int, val lang: String)

    private var fallbackSubsCache: List<SubtitleEntry>? = null
    private var fallbackSubsAt = 0L

    private var files1080Cache: List<DavFile>? = null
    private var files1080At = 0L

    private suspend fun listMkvFiles1080(): List<DavFile> {
        files1080Cache?.let { cached ->
            if (System.currentTimeMillis() - files1080At < SUBS_CACHE_MS) return cached
        }
        val files = listMkvFiles(PATH_1080)
        if (files.isNotEmpty()) {
            files1080Cache = files
            files1080At = System.currentTimeMillis()
        }
        return files
    }

    /** Depth-1 PROPFIND; returns every entry with its raw (still percent-encoded) href. */
    private suspend fun propfind(path: String): List<DavEntry> {
        val body = app.custom(
            "PROPFIND",
            "$mainUrl$path",
            headers = mapOf(
                "Depth" to "1",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            requestBody = PROPFIND_BODY
        ).text
        return Jsoup.parse(body, "", Parser.xmlParser())
            .select("d|response")
            .mapNotNull { entry ->
                val href = entry.selectFirst("d|href")?.text() ?: return@mapNotNull null
                val isCollection =
                    entry.selectFirst("d|resourcetype")?.selectFirst("d|collection") != null
                DavEntry(href, isCollection, URLDecoder.decode(href.substringAfterLast('/'), "UTF-8"))
            }
    }

    private suspend fun listMkvFiles(path: String): List<DavFile> {
        return try {
            propfind(path)
                .filterNot { it.isCollection }
                .filter { it.fileName.endsWith(".mkv", true) }
                .map { DavFile(it.href, it.fileName) }
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
    }

    /** Fallback for episodes not bundled in the repo: pick ONE file, Below > Above > Uncut. */
    private suspend fun listFallbackSubs(): List<SubtitleEntry> {
        fallbackSubsCache?.let { cached ->
            if (System.currentTimeMillis() - fallbackSubsAt < SUBS_CACHE_MS) return cached
        }
        val entries = ArrayList<SubtitleEntry>()
        try {
            val queue = ArrayDeque(listOf(PATH_SUBS))
            var depth = 0
            while (queue.isNotEmpty() && depth < 3) {
                val folders = ArrayList<String>()
                for (path in queue) {
                    propfind(path).forEach { entry ->
                        when {
                            entry.isCollection -> folders.add(entry.href)
                            entry.fileName.endsWith(".ass", true) -> {
                                val decodedHref = URLDecoder.decode(entry.href, "UTF-8")
                                val segments = decodedHref.split('/').filter { it.isNotBlank() }
                                val parent = segments.dropLast(1).lastOrNull() ?: ""
                                val isUncut = segments.any { it.equals("Uncut", true) }
                                val lang = if (isUncut) "English (Uncut - $parent)" else "English ($parent)"
                                SUBTITLE_PATTERNS.firstNotNullOfOrNull {
                                    it.find(entry.fileName)?.groupValues?.get(1)?.toIntOrNull()
                                }?.let { ep ->
                                    entries.add(SubtitleEntry(entry.href, ep, lang))
                                }
                            }
                        }
                    }
                }
                queue.clear()
                queue.addAll(folders)
                depth++
            }
        } catch (e: Exception) {
            logError(e)
        }
        fallbackSubsCache = entries
        fallbackSubsAt = System.currentTimeMillis()
        return entries
    }

    private suspend fun subtitleFor(episode: Int): SubtitleFile? {
        // Bundled: plain-ASCII raw.githubusercontent URL, verified Content-Type text/plain,
        // no percent-encoding traps — this is what makes subs load in mpv-based players.
        if (episode in BUNDLED_SUBS_MIN..BUNDLED_SUBS_MAX) {
            return newSubtitleFile("English", "$SUBS_RAW_BASE/ep%03d.srt".format(episode))
        }
        // Future episodes: pick a single best file from the live share.
        val pick = listFallbackSubs()
            .filter { it.episode == episode }
            .minByOrNull { entry ->
                when {
                    !entry.href.contains("Uncut", true) && entry.href.contains("Below", true) -> 0
                    !entry.href.contains("Uncut", true) && entry.href.contains("Above", true) -> 1
                    entry.href.contains("Below", true) -> 2
                    else -> 3
                }
            } ?: return null
        return newSubtitleFile(pick.lang, "$mainUrl${pick.href}")
    }

    private suspend fun link(url: String, label: String, quality: Int): ExtractorLink {
        return newExtractorLink(this.name, label, url) {
            this.quality = quality
            this.referer = ""
        }
    }

    private fun seriesEntry(): SearchResponse {
        return newAnimeSearchResponse(SERIES_TITLE, "$mainUrl$SERIES_URL", TvType.Anime) {
            this.posterUrl = POSTER_URL
        }
    }

    private fun movieEntry(): SearchResponse {
        return newMovieSearchResponse(MOVIE_TITLE, "$mainUrl$MOVIE_URL", TvType.AnimeMovie) {
            this.posterUrl = POSTER_URL
        }
    }

    private fun episodeNumber(fileName: String): Int? {
        return EPISODE_PATTERNS.firstNotNullOfOrNull { it.find(fileName) }
            ?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun buildEpisodes(
        files4k: List<DavFile>,
        files1080: List<DavFile>
    ): List<Episode> {
        val byEp4k = files4k.mapNotNull { file ->
            episodeNumber(file.fileName)?.let { it to file }
        }.toMap()
        val byEp1080 = files1080.mapNotNull { file ->
            episodeNumber(file.fileName)?.let { it to file }
        }.toMap()

        return (byEp4k.keys + byEp1080.keys)
            .sorted()
            .distinct()
            .map { ep ->
                // Plain URL data — ShonenX splits its own episode id on '|' and
                // passes parts[0] to loadLinks, so the data must be a bare URL.
                val data = byEp4k[ep]?.let { "$mainUrl${it.href}" }
                    ?: byEp1080[ep]?.let { "$mainUrl${it.href}" }
                    ?: return@map null
                newEpisode(data) {
                    this.name = "Episode $ep"
                    this.episode = ep
                    this.season = 1
                }
            }
            .filterNotNull()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return newHomePageResponse(
            listOf(
                HomePageList("Series", listOf(seriesEntry()), true),
                HomePageList("Movies", listOf(movieEntry()), true)
            )
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (page != 1) return null
        val q = query.trim().lowercase()
        val entries = listOf(seriesEntry(), movieEntry())
        if (q.isEmpty()) return entries.toNewSearchResponseList()

        val aliases = mapOf(
            0 to listOf("renegade immortal", "xian ni", "仙逆"),
            1 to listOf("battle of the gods", "renegade immortal movie", "仙逆剧场版", "movie")
        )
        val matches = entries.filterIndexed { index, _ ->
            aliases[index]?.any { alias -> alias.contains(q) || q.contains(alias) } == true
        }
        return matches.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val files4k = listMkvFiles(PATH_4K)
        val files1080 = listMkvFiles(PATH_1080)
        return when {
            url.endsWith(SERIES_URL) -> newTvSeriesLoadResponse(
                SERIES_TITLE, url, TvType.Anime, buildEpisodes(files4k, files1080)
            ) {
                this.posterUrl = POSTER_URL
                this.plot =
                    "Wang Lin is a bright boy from a family shunned by their relatives. " +
                        "Granted one chance to walk the path of immortality with only mediocre talent, " +
                        "he takes step after bloody step to forge his own road to the heavens. " +
                        "BonoboSubs 4K and 1080p HEVC rips with English subtitles."
                this.year = 2023
                this.tags = listOf("Donghua", "Xianxia", "Cultivation")
            }

            url.endsWith(MOVIE_URL) -> {
                val movie4k = files4k.firstOrNull { MOVIE_PATTERN.containsMatchIn(it.fileName) }
                    ?: throw ErrorLoadingException("Movie file not found on BonoboSubs")
                val movie1080 = files1080.firstOrNull { MOVIE_PATTERN.containsMatchIn(it.fileName) }
                val data = "$mainUrl${movie4k.href}"
                newMovieLoadResponse(MOVIE_TITLE, url, TvType.AnimeMovie, data) {
                    this.posterUrl = POSTER_URL
                    this.plot =
                        "Renegade Immortal movie — Battle of the Gods. " +
                            "Watch after episode 76. BonoboSubs 4K and 1080p HEVC with English subtitles."
                    this.year = 2025
                    this.tags = listOf("Donghua", "Xianxia", "Movie")
                }
            }

            else -> throw ErrorLoadingException("Unknown url: $url")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // ShonenX splits its episode id "$url|$episodeNumber" on '|' and hands
        // parts[0] here, so data is normally a bare URL. Tolerate stale v6 ids
        // ("144|<4k>|<1080p>") left in resume/bookmark caches by picking the
        // first URL out of them.
        val videoUrl = if (data.startsWith("http")) data
        else data.split("|").firstOrNull { it.startsWith("http") } ?: return true

        val videoName = URLDecoder.decode(videoUrl.substringAfterLast('/'), "UTF-8")
        val isMovie = MOVIE_PATTERN.containsMatchIn(videoName)
        val ep = episodeNumber(videoName)
        val is4k = videoUrl.contains("/4k/", ignoreCase = true)

        // Subtitles MUST fire before the link callbacks: the bridge attaches the
        // subtitle list to each link at callback time.
        if (!isMovie) {
            ep?.let { e -> subtitleFor(e)?.let { subtitleCallback(it) } }
        }

        callback(
            link(
                videoUrl,
                if (is4k) "$name 4K HEVC" else "$name 1080p",
                if (is4k) Qualities.P2160.value else Qualities.P1080.value
            )
        )

        // Emit the other quality from the live listing (exact hrefs, cached)
        val other = if (isMovie) {
            (if (is4k) listMkvFiles1080() else listMkvFiles(PATH_4K))
                .firstOrNull { MOVIE_PATTERN.containsMatchIn(it.fileName) }
        } else {
            (if (is4k) listMkvFiles1080() else listMkvFiles(PATH_4K))
                .firstOrNull { episodeNumber(it.fileName) == ep }
        }
        other?.let { file ->
            val otherUrl = "$mainUrl${file.href}"
            val otherIs4k = otherUrl.contains("/4k/", ignoreCase = true)
            callback(
                link(
                    otherUrl,
                    if (otherIs4k) "$name 4K HEVC" else "$name 1080p",
                    if (otherIs4k) Qualities.P2160.value else Qualities.P1080.value
                )
            )
        }
        return true
    }
}
