package com.bonobosubs

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
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
import java.util.concurrent.ConcurrentHashMap

/**
 * BonoboSubs - Renegade Immortal (Xian Ni) series + movie.
 *
 * Videos come straight from the public Nextcloud share
 * (https://bonobosubs.ovh/s/download). The share also publishes the English
 * subtitles as ASS, which ShonenX's default subtitle renderer cannot display.
 * [scripts/sync_subs.py] mirrors the share's latest ASS files into SRT with the
 * original cue timings and pushes them to `subs/` in this repository, so the
 * player always gets a format it can render. The sync runs every 15 minutes
 * (see .github/workflows/sync-subs.yml), which is what makes new episodes and
 * corrections show up automatically.
 */
class BonoboSubsProvider : MainAPI() {
    override var mainUrl = "https://bonobosubs.ovh"
    override var name = "BonoboSubs"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override var lang = "en"
    override val hasMainPage = true

    companion object {
        private const val SHARE_TOKEN = "download"
        private const val PATH_4K = "/public.php/dav/files/$SHARE_TOKEN/4k/"
        private const val PATH_1080 = "/public.php/dav/files/$SHARE_TOKEN/1080p/"

        private const val SUBS_BASE =
            "https://raw.githubusercontent.com/2Gbps/CloudStream-BonoboSubs/main/subs"
        private const val MOVIE_SUBS_URL = "$SUBS_BASE/movie.srt"

        private const val SERIES_URL = "/renegade-immortal"
        private const val MOVIE_URL = "/battle-of-the-gods"
        private const val SERIES_TITLE = "Renegade Immortal"
        private const val MOVIE_TITLE = "Renegade Immortal: Battle of the Gods"
        private const val POSTER_URL =
            "https://media.kitsu.app/anime/48036/poster_image/large-b4d32359a87f74424144a75ffcfbdcae.jpeg"

        private const val SECONDARY_LISTING_TTL_MS = 10 * 60 * 1000L

        private val PROPFIND_BODY by lazy {
            """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"""
                .trimIndent().toRequestBody("application/xml".toMediaTypeOrNull())
        }

        // [BonoboSubs]Renegade Immortal - Xian Ni Episode 117 .mkv   (1080p releases)
        // [BonoboSubs][4k]Renegade Immortal - 仙逆 Xian Ni - 147.mkv  (4K releases)
        private val EPISODE_PATTERNS = listOf(
            Regex("""Episode\s*(\d{1,4})\s*\.mkv$""", RegexOption.IGNORE_CASE),
            Regex("""Xian\s*Ni\s*-\s*(\d{1,4})\s*\.mkv$""", RegexOption.IGNORE_CASE)
        )
        private val MOVIE_PATTERN =
            Regex("""Renegade Immortal Movie.*\.mkv$""", RegexOption.IGNORE_CASE)
    }

    private data class DavFile(val href: String, val fileName: String)
    private data class DavEntry(val href: String, val isCollection: Boolean, val fileName: String)

    private val listingCache = ConcurrentHashMap<String, List<DavFile>>()
    private val listingTimes = ConcurrentHashMap<String, Long>()

    private fun seriesEntry() = newAnimeSearchResponse(SERIES_TITLE, "$mainUrl$SERIES_URL", TvType.Anime) {
        posterUrl = POSTER_URL
    }

    private fun movieEntry() = newMovieSearchResponse(MOVIE_TITLE, "$mainUrl$MOVIE_URL", TvType.AnimeMovie) {
        posterUrl = POSTER_URL
    }

    private fun episodeNumber(fileName: String) =
        EPISODE_PATTERNS.firstNotNullOfOrNull { it.find(fileName) }?.groupValues?.get(1)?.toIntOrNull()

    private fun isMovie(fileName: String) = MOVIE_PATTERN.containsMatchIn(fileName)

    private suspend fun propfind(path: String): List<DavEntry> {
        val body = app.custom(
            "PROPFIND",
            "$mainUrl$path",
            headers = mapOf("Depth" to "1", "X-Requested-With" to "XMLHttpRequest"),
            requestBody = PROPFIND_BODY
        ).text
        return Jsoup.parse(body, "", Parser.xmlParser()).select("d|response").mapNotNull {
            val href = it.selectFirst("d|href")?.text() ?: return@mapNotNull null
            val isCollection = it.selectFirst("d|resourcetype")?.selectFirst("d|collection") != null
            DavEntry(href, isCollection, URLDecoder.decode(href.substringAfterLast('/'), "UTF-8"))
        }
    }

    /**
     * Depth-1 listing of the share. A non-zero [maxAgeMs] serves the cached
     * listing (used for the secondary quality link, where a few minutes of
     * staleness is harmless) and falls back to the cache on network errors.
     */
    private suspend fun listMkvFiles(path: String, maxAgeMs: Long = 0L): List<DavFile> {
        val cached = listingCache[path]
        if (cached != null && maxAgeMs > 0L &&
            System.currentTimeMillis() - (listingTimes[path] ?: 0L) < maxAgeMs
        ) {
            return cached
        }
        return try {
            val files = propfind(path)
                .filter { !it.isCollection && it.fileName.endsWith(".mkv", true) }
                .map { DavFile(it.href, it.fileName) }
            if (files.isNotEmpty()) {
                listingCache[path] = files
                listingTimes[path] = System.currentTimeMillis()
            }
            files
        } catch (e: Exception) {
            logError(e)
            cached ?: emptyList()
        }
    }

    private fun buildEpisodes(files4k: List<DavFile>, files1080: List<DavFile>): List<Episode> {
        val by4k = files4k.mapNotNull { episodeNumber(it.fileName)?.let { number -> number to it } }.toMap()
        val by1080 = files1080.mapNotNull { episodeNumber(it.fileName)?.let { number -> number to it } }.toMap()
        return (by4k.keys + by1080.keys).sorted().distinct().mapNotNull { episode ->
            val file = by4k[episode] ?: by1080[episode] ?: return@mapNotNull null
            newEpisode("$mainUrl${file.href}") {
                name = "Episode $episode"
                this.episode = episode
                season = 1
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest) = newHomePageResponse(
        listOf(
            HomePageList("Series", listOf(seriesEntry()), true),
            HomePageList("Movies", listOf(movieEntry()), true)
        )
    )

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (page != 1) return null
        val q = query.trim().lowercase()
        val entries = listOf(seriesEntry(), movieEntry())
        if (q.isEmpty()) return entries.toNewSearchResponseList()
        val aliases = mapOf(
            0 to listOf("renegade immortal", "xian ni", "仙逆"),
            1 to listOf("battle of the gods", "renegade immortal movie", "仙逆剧场版", "movie")
        )
        return entries.filterIndexed { index, _ ->
            aliases[index]?.any { it.contains(q) || q.contains(it) } == true
        }.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val files4k = listMkvFiles(PATH_4K)
        val files1080 = listMkvFiles(PATH_1080)
        return when {
            url.endsWith(SERIES_URL) -> newTvSeriesLoadResponse(
                SERIES_TITLE, url, TvType.Anime, buildEpisodes(files4k, files1080)
            ) {
                posterUrl = POSTER_URL
                plot = "Wang Lin walks the path of immortality. BonoboSubs 4K/1080p HEVC with English subtitles."
                year = 2023
                tags = listOf("Donghua", "Xianxia", "Cultivation")
            }
            url.endsWith(MOVIE_URL) -> {
                val movie = files4k.firstOrNull { isMovie(it.fileName) }
                    ?: files1080.firstOrNull { isMovie(it.fileName) }
                    ?: throw ErrorLoadingException("Movie not found")
                newMovieLoadResponse(MOVIE_TITLE, url, TvType.AnimeMovie, "$mainUrl${movie.href}") {
                    posterUrl = POSTER_URL
                    plot = "Battle of the Gods. Watch after episode 76. BonoboSubs 4K/1080p HEVC with English subtitles."
                    year = 2025
                    tags = listOf("Donghua", "Xianxia", "Movie")
                }
            }
            else -> throw ErrorLoadingException("Unknown url")
        }
    }

    /** The bridge hands over the raw episode data URL, but stale caches can carry
     *  pipe-separated legacy data or a bare episode number; handle all three. */
    private suspend fun resolveVideoUrl(data: String): String? {
        if (data.startsWith("http")) return data
        data.split("|").firstOrNull { it.startsWith("http") }?.let { return it }
        val episode = data.trim().toIntOrNull() ?: return null
        return listMkvFiles(PATH_4K).firstOrNull { episodeNumber(it.fileName) == episode }
            ?.let { "$mainUrl${it.href}" }
            ?: listMkvFiles(PATH_1080).firstOrNull { episodeNumber(it.fileName) == episode }
                ?.let { "$mainUrl${it.href}" }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoUrl = resolveVideoUrl(data) ?: return true
        val fileName = URLDecoder.decode(videoUrl.substringAfterLast('/'), "UTF-8")
        val movie = isMovie(fileName)
        val episode = episodeNumber(fileName)
        val is4k = videoUrl.contains("/4k/", ignoreCase = true)

        // Subtitles must be emitted before the link callbacks: the runtime
        // snapshots the subtitle list into each link when the callback fires.
        when {
            movie -> subtitleCallback(newSubtitleFile("English", MOVIE_SUBS_URL))
            episode != null -> subtitleCallback(
                newSubtitleFile("English", "$SUBS_BASE/ep%03d.srt".format(episode))
            )
        }

        callback(link(videoUrl, is4k))

        val otherPath = if (is4k) PATH_1080 else PATH_4K
        val other = listMkvFiles(otherPath, SECONDARY_LISTING_TTL_MS).firstOrNull { file ->
            if (movie) isMovie(file.fileName) else episodeNumber(file.fileName) == episode
        }
        other?.let { callback(link("$mainUrl${it.href}", it.href.contains("/4k/", ignoreCase = true))) }
        return true
    }

    private suspend fun link(url: String, is4k: Boolean): ExtractorLink =
        newExtractorLink(name, if (is4k) "$name 4K HEVC" else "$name 1080p", url) {
            quality = if (is4k) Qualities.P2160.value else Qualities.P1080.value
            referer = ""
        }
}
