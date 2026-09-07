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
        private const val DAV_PATH = "/public.php/dav/files/$SHARE_TOKEN/4k/"

        // Internal slugs, never fetched as http
        private const val SERIES_URL = "/renegade-immortal"
        private const val MOVIE_URL = "/battle-of-the-gods"

        private const val SERIES_TITLE = "Renegade Immortal"
        private const val MOVIE_TITLE = "Renegade Immortal: Battle of the Gods"

        private const val POSTER_URL =
            "https://media.kitsu.app/anime/48036/poster_image/large-b4d32359a87f74424144a75ffcfbdcae.jpeg"

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
        // [BonoboSubs][4k]Renegade Immortal - Xian Ni Episode 077.mkv (older releases)
        private val EPISODE_PATTERNS = listOf(
            Regex("""Xian Ni - (\d{1,4})\.mkv$""", RegexOption.IGNORE_CASE),
            Regex("""Xian Ni Episode (\d{1,4})\.mkv$""", RegexOption.IGNORE_CASE)
        )

        private val MOVIE_PATTERN =
            Regex("""Renegade Immortal Movie.*\.mkv$""", RegexOption.IGNORE_CASE)
    }

    private data class DavFile(val href: String, val fileName: String)

    private suspend fun list4kFiles(): List<DavFile> {
        return try {
            val body = app.custom(
                "PROPFIND",
                "$mainUrl$DAV_PATH",
                headers = mapOf(
                    "Depth" to "1",
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                requestBody = PROPFIND_BODY
            ).text
            Jsoup.parse(body, "", Parser.xmlParser())
                .select("d|response")
                .mapNotNull { entry ->
                    val href = entry.selectFirst("d|href")?.text() ?: return@mapNotNull null
                    // Skip folders, only keep files
                    if (entry.selectFirst("d|resourcetype")?.selectFirst("d|collection") != null) {
                        return@mapNotNull null
                    }
                    DavFile(href, URLDecoder.decode(href.substringAfterLast('/'), "UTF-8"))
                }
        } catch (e: Exception) {
            logError(e)
            emptyList()
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

    private fun buildEpisodes(files: List<DavFile>): List<Episode> {
        return files.mapNotNull { file ->
            val epNum = EPISODE_PATTERNS.firstNotNullOfOrNull { it.find(file.fileName) }
                ?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            file to epNum
        }
            .sortedBy { it.second }
            .distinctBy { it.second }
            .map { (file, epNum) ->
                newEpisode("$mainUrl${file.href}") {
                    this.name = "Episode $epNum"
                    this.episode = epNum
                    this.season = 1
                }
            }
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
        val files = list4kFiles()
        return when {
            url.endsWith(SERIES_URL) -> newTvSeriesLoadResponse(
                SERIES_TITLE, url, TvType.Anime, buildEpisodes(files)
            ) {
                this.posterUrl = POSTER_URL
                this.plot =
                    "Wang Lin is a bright boy from a family shunned by their relatives. " +
                        "Granted one chance to walk the path of immortality with only mediocre talent, " +
                        "he takes step after bloody step to forge his own road to the heavens. " +
                        "BonoboSubs 4K HEVC rips with embedded English subtitles."
                this.year = 2023
                this.tags = listOf("Donghua", "Xianxia", "Cultivation")
            }

            url.endsWith(MOVIE_URL) -> {
                val movieFile = files.firstOrNull { MOVIE_PATTERN.containsMatchIn(it.fileName) }
                    ?: throw ErrorLoadingException("Movie file not found on BonoboSubs")
                newMovieLoadResponse(
                    MOVIE_TITLE, url, TvType.AnimeMovie, "$mainUrl${movieFile.href}"
                ) {
                    this.posterUrl = POSTER_URL
                    this.plot =
                        "Renegade Immortal movie — Battle of the Gods. " +
                            "Watch after episode 76. BonoboSubs 4K HEVC with embedded English subtitles."
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
        // Direct WebDAV file URL, verified to support range requests (HTTP 206)
        callback(
            newExtractorLink(
                this.name,
                "$name 4K HEVC",
                data
            ) {
                quality = Qualities.P2160.value
                referer = ""
            }
        )
        return true
    }
}
