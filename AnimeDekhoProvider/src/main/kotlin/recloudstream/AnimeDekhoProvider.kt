package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeDekhoProvider : MainAPI() {
    override var mainUrl = "https://animedekho.app"
    override var name = "Anime Dekho"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Cartoon, TvType.Anime, TvType.AnimeMovie, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/category/anime/" to "Anime",
        "$mainUrl/category/cartoon/" to "Cartoon",
        "$mainUrl/category/crunchyroll/" to "Crunchyroll",
        "$mainUrl/category/hindi-dub/" to "Hindi",
        "$mainUrl/category/tamil/" to "Tamil",
        "$mainUrl/category/telugu/" to "Telugu"
    )

    private val ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url, headers = mapOf("User-Agent" to ua)).document
        return newHomePageResponse(request.name, doc.select("article").mapNotNull { it.toSearchResult() })
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a.lnk-blk") ?: selectFirst("a") ?: return null
        val href = anchor.attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = selectFirst("header h2")?.text()?.trim() ?: return null
        var poster = selectFirst("div figure img")?.attr("src")
        if (poster != null && poster.contains("data:image")) {
            poster = selectFirst("div figure img")?.attr("data-lazy-src")
        }
        return newAnimeSearchResponse(title, Media(href, poster, null).toJson(), TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val enc = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$enc", headers = mapOf("User-Agent" to ua)).document
        return newSearchResponseList(doc.select("ul[data-results] li article").mapNotNull { it.toSearchResult() })
    }

    override suspend fun load(url: String): LoadResponse? {
        val media = try { parseJson<Media>(url) } catch (e: Exception) { Media(url, null, null) }
        val doc = app.get(media.url, headers = mapOf("User-Agent" to ua)).document

        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()?.substringAfter("Watch Online ")?.trim() ?: "No Title"
        val poster = doc.selectFirst("div.post-thumbnail figure img")?.attr("src") ?: media.poster
        val plot = doc.selectFirst("div.entry-content p")?.text()?.trim()
        val year = doc.selectFirst("span.year")?.text()?.trim()?.toIntOrNull()
        val genres = doc.select("ul.details-lst li:contains(Genres) a").map { it.text() }

        val episodes = doc.select("ul.seasons-lst li")
        if (episodes.isNotEmpty()) {
            val epList = episodes.mapNotNull { li ->
                val epTitle = li.selectFirst("h3.title")?.ownText() ?: "Episode"
                val epUrl = li.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val epPoster = li.selectFirst("div > div > figure > img")?.attr("src")
                val epText = li.selectFirst("h3.title > span")?.text() ?: ""
                val season = epText.substringAfter("S").substringBefore("-").toIntOrNull() ?: 1
                val epNum = Regex("E(\\d+)").find(epText)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                newEpisode(Media(epUrl, null, 2).toJson()) {
                    name = epTitle
                    this.season = season
                    episode = epNum
                    posterUrl = epPoster
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, epList) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                tags = genres
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, Media(media.url, null, 1).toJson()) {
            posterUrl = poster
            this.plot = plot
            this.year = year
            tags = genres
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val media = try { parseJson<Media>(data) } catch (e: Exception) { return false }
        val headers = mapOf("User-Agent" to ua, "Cookie" to "toronites_server=vidstream")
        val doc = app.get(media.url, headers = headers).document

        doc.select("iframe.serversel[src]").amap { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                try {
                    val embedDoc = app.get(src, headers = headers).document
                    embedDoc.select("iframe[src]").amap { inner ->
                        val innerSrc = inner.attr("src")
                        if (innerSrc.isNotBlank()) loadExtractor(innerSrc, subtitleCallback, callback)
                    }
                } catch (e: Exception) {}
            }
        }

        val bodyClass = doc.selectFirst("body")?.attr("class") ?: ""
        val termMatch = Regex("(?:term|postid)-(\\d+)").find(bodyClass)?.groupValues?.get(1)
        if (!termMatch.isNullOrBlank()) {
            val mediaType = media.mediaType ?: 1
            for (i in 0..10) {
                try {
                    val page = app.get("$mainUrl/?trdekho=$i&trid=$termMatch&trtype=$mediaType", headers = headers, allowRedirects = true)
                    page.document.select("iframe[src]").amap { iframe ->
                        val src = iframe.attr("src")
                        if (src.isNotBlank() && !src.startsWith("about:")) {
                            loadExtractor(src, subtitleCallback, callback)
                        }
                    }
                } catch (e: Exception) {}
            }
        }

        return true
    }

    data class Media(val url: String, val poster: String? = null, val mediaType: Int? = null)
}
