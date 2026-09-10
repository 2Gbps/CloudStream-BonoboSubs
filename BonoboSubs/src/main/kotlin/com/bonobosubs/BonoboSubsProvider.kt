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
        private const val SHARE_TOKEN = "download"
        private const val PATH_4K = "/public.php/dav/files/$SHARE_TOKEN/4k/"
        private const val PATH_1080 = "/public.php/dav/files/$SHARE_TOKEN/1080p/"
        private const val SUBS_RAW = "https://raw.githubusercontent.com/2Gbps/CloudStream-BonoboSubs/main/subs"

        private const val SERIES_URL = "/renegade-immortal"
        private const val MOVIE_URL = "/battle-of-the-gods"
        private const val SERIES_TITLE = "Renegade Immortal"
        private const val MOVIE_TITLE = "Renegade Immortal: Battle of the Gods"
        private const val POSTER_URL =
            "https://media.kitsu.app/anime/48036/poster_image/large-b4d32359a87f74424144a75ffcfbdcae.jpeg"

        private val PROPFIND_BODY by lazy {
            """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"""
                .trimIndent().toRequestBody("application/xml".toMediaTypeOrNull())
        }

        private val EPISODE_PATTERNS = listOf(
            Regex("""Xian Ni - (\d{1,4})\.mkv$""", RegexOption.IGNORE_CASE),
            Regex("""Xian Ni Episode (\d{1,4})\.mkv$""", RegexOption.IGNORE_CASE)
        )
        private val MOVIE_PATTERN = Regex("""Renegade Immortal Movie.*\.mkv$""", RegexOption.IGNORE_CASE)
    }

    private data class DavFile(val href: String, val fileName: String)
    private data class DavEntry(val href: String, val isCollection: Boolean, val fileName: String)

    private var files1080Cache: List<DavFile>? = null
    private var files1080At = 0L

    private suspend fun listMkvFiles1080(): List<DavFile> {
        files1080Cache?.let { if (System.currentTimeMillis() - files1080At < 600_000L) return it }
        val files = listMkvFiles(PATH_1080)
        if (files.isNotEmpty()) { files1080Cache = files; files1080At = System.currentTimeMillis() }
        return files
    }

    private suspend fun propfind(path: String): List<DavEntry> {
        val body = app.custom("PROPFIND", "$mainUrl$path",
            headers = mapOf("Depth" to "1", "X-Requested-With" to "XMLHttpRequest"),
            requestBody = PROPFIND_BODY
        ).text
        return Jsoup.parse(body, "", Parser.xmlParser()).select("d|response").mapNotNull {
            val href = it.selectFirst("d|href")?.text() ?: return@mapNotNull null
            val col = it.selectFirst("d|resourcetype")?.selectFirst("d|collection") != null
            DavEntry(href, col, URLDecoder.decode(href.substringAfterLast('/'), "UTF-8"))
        }
    }

    private suspend fun listMkvFiles(path: String): List<DavFile> = try {
        propfind(path).filter { !it.isCollection && it.fileName.endsWith(".mkv", true) }
            .map { DavFile(it.href, it.fileName) }
    } catch (e: Exception) { logError(e); emptyList() }

    private fun seriesEntry() = newAnimeSearchResponse(SERIES_TITLE, "$mainUrl$SERIES_URL", TvType.Anime) { posterUrl = POSTER_URL }
    private fun movieEntry() = newMovieSearchResponse(MOVIE_TITLE, "$mainUrl$MOVIE_URL", TvType.AnimeMovie) { posterUrl = POSTER_URL }
    private fun episodeNumber(fn: String) = EPISODE_PATTERNS.firstNotNullOfOrNull { it.find(fn) }?.groupValues?.get(1)?.toIntOrNull()

    private fun buildEpisodes(f4k: List<DavFile>, f1080: List<DavFile>): List<Episode> {
        val by4k = f4k.mapNotNull { episodeNumber(it.fileName)?.let { n -> n to it } }.toMap()
        val by1080 = f1080.mapNotNull { episodeNumber(it.fileName)?.let { n -> n to it } }.toMap()
        return (by4k.keys + by1080.keys).sorted().distinct().mapNotNull { ep ->
            val data = by4k[ep]?.let { "$mainUrl${it.href}" } ?: by1080[ep]?.let { "$mainUrl${it.href}" } ?: return@mapNotNull null
            newEpisode(data) { name = "Episode $ep"; episode = ep; season = 1 }
        }
    }

    private suspend fun link(url: String, label: String, quality: Int) =
        newExtractorLink(name, label, url) { this.quality = quality; referer = "" }

    override suspend fun getMainPage(page: Int, request: MainPageRequest) = newHomePageResponse(
        listOf(HomePageList("Series", listOf(seriesEntry()), true), HomePageList("Movies", listOf(movieEntry()), true))
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
        return entries.filterIndexed { i, _ -> aliases[i]?.any { it.contains(q) || q.contains(it) } == true }
            .toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val f4k = listMkvFiles(PATH_4K)
        val f1080 = listMkvFiles(PATH_1080)
        return when {
            url.endsWith(SERIES_URL) -> newTvSeriesLoadResponse(SERIES_TITLE, url, TvType.Anime, buildEpisodes(f4k, f1080)) {
                posterUrl = POSTER_URL; plot = "Wang Lin walks the path of immortality. BonoboSubs 4K/1080p HEVC with embedded English subs."; year = 2023; tags = listOf("Donghua", "Xianxia", "Cultivation")
            }
            url.endsWith(MOVIE_URL) -> {
                val m4k = f4k.firstOrNull { MOVIE_PATTERN.containsMatchIn(it.fileName) } ?: throw ErrorLoadingException("Movie not found")
                newMovieLoadResponse(MOVIE_TITLE, url, TvType.AnimeMovie, "$mainUrl${m4k.href}") {
                    posterUrl = POSTER_URL; plot = "Battle of the Gods. Watch after ep 76. BonoboSubs 4K/1080p HEVC."; year = 2025; tags = listOf("Donghua", "Xianxia", "Movie")
                }
            }
            else -> throw ErrorLoadingException("Unknown url")
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val videoUrl = if (data.startsWith("http")) data
        else data.split("|").firstOrNull { it.startsWith("http") }
            ?: data.trim().toIntOrNull()?.let { ep ->
                listMkvFiles(PATH_4K).firstOrNull { episodeNumber(it.fileName) == ep }?.let { "$mainUrl${it.href}" }
            } ?: return true

        val name2 = URLDecoder.decode(videoUrl.substringAfterLast('/'), "UTF-8")
        val is4k = videoUrl.contains("/4k/", ignoreCase = true)

        // Provide external subs from bundled SRT (original ASS timing, matches embedded tracks)
        val ep = episodeNumber(name2)
        if (ep != null) {
            subtitleCallback(newSubtitleFile("English", "$SUBS_RAW/ep%03d.srt".format(ep)))
        }

        callback(link(videoUrl, if (is4k) "$name 4K HEVC" else "$name 1080p", if (is4k) Qualities.P2160.value else Qualities.P1080.value))

        val other = (if (is4k) listMkvFiles1080() else listMkvFiles(PATH_4K))
            .firstOrNull { episodeNumber(it.fileName) == ep || MOVIE_PATTERN.containsMatchIn(it.fileName) && MOVIE_PATTERN.containsMatchIn(name2) }
        other?.let {
            val oUrl = "$mainUrl${it.href}"; val o4k = oUrl.contains("/4k/", ignoreCase = true)
            callback(link(oUrl, if (o4k) "$name 4K HEVC" else "$name 1080p", if (o4k) Qualities.P2160.value else Qualities.P1080.value))
        }
        return true
    }
}
