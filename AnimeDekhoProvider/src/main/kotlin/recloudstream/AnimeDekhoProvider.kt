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
    override val supportedTypes = setOf(
        TvType.Cartoon, TvType.Anime, TvType.AnimeMovie, TvType.Movie
    )

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
        val home = doc.select("article").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a.lnk-blk") ?: selectFirst("a") ?: return null
        val href = anchor.attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = selectFirst("header h2")?.text()?.trim()
            ?: selectFirst(".entry-title")?.text()?.trim()
            ?: return null

        var poster = selectFirst("div figure img")?.attr("src")
        if (poster != null && poster.contains("data:image")) {
            poster = selectFirst("div figure img")?.attr("data-lazy-src")
        }

        val media = Media(href, poster, null)
        return newAnimeSearchResponse(title, media.toJson(), TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val enc = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/?s=$enc"
        val doc = app.get(url, headers = mapOf("User-Agent" to ua)).document
        val results = doc.select("ul[data-results] li article").mapNotNull { it.toSearchResult() }
        return newSearchResponseList(results)
    }

    override suspend fun load(url: String): LoadResponse? {
        val media = try { parseJson<Media>(url) } catch (e: Exception) { Media(url, null, null) }
        val doc = app.get(media.url, headers = mapOf("User-Agent" to ua)).document

        // Title from h1.entry-title
        var title = doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?.substringAfter("Watch Online ")?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringAfter("Watch Online ")?.substringBefore(" Movie in Hindi Dubbed Free")?.trim()
            ?: "No Title"

        // Poster
        val poster = doc.selectFirst("div.post-thumbnail figure img")?.attr("src") ?: media.poster

        // Plot
        val plot = doc.selectFirst("div.entry-content p")?.text()?.trim()
            ?: doc.selectFirst("meta[name=twitter:description]")?.attr("content")

        // Year
        val year = doc.selectFirst("span.year")?.text()?.trim()?.toIntOrNull()
            ?: doc.selectFirst("meta[property=og:updated_time]")?.attr("content")?.substringBefore("-")?.toIntOrNull()

        // Genres
        val genres = doc.select("ul.details-lst li:contains(Genres) a").map { it.text() }

        // Series? — has seasons-lst
        val episodes = doc.select("ul.seasons-lst li")
        if (episodes.isNotEmpty()) {
            val epList = episodes.mapNotNull { li ->
                val epTitle = li.selectFirst("h3.title")?.ownText() ?: "null"
                val epUrl = li.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val epPoster = li.selectFirst("div > div > figure > img")?.attr("src")
                val epText = li.selectFirst("h3.title > span")?.text() ?: ""
                // S1-E1 format parse
                val seasonStr = epText.substringAfter("S").substringBefore("-")
                val epStr = Regex("E(\\d+)").find(epText)?.groupValues?.get(1)
                val season = seasonStr.toIntOrNull() ?: 1
                val epNum = epStr?.toIntOrNull() ?: 1

                val m = Media(epUrl, null, 2)
                newEpisode(m.toJson()) {
                    this.name = epTitle
                    this.season = season
                    this.episode = epNum
                    this.posterUrl = epPoster
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, epList) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres
            }
        }

        // Movie
        val m = Media(media.url, null, 1)
        return newMovieLoadResponse(title, url, TvType.Movie, m.toJson()) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = genres
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val media = try { parseJson<Media>(data) } catch (e: Exception) { return false }

        val headers = mapOf(
            "User-Agent" to ua,
            "Cookie" to "toronites_server=vidstream"
        )

        // 1. Fetch page
        val doc = app.get(media.url, headers = headers).document

        // 2. Method A: iframe.serversel[src] direct hai
        val iframes = doc.select("iframe.serversel[src]")
        iframes.amap { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                // Fetch embed page, get inner iframe
                try {
                    val embedDoc = app.get(src, headers = headers).document
                    val innerIframe = embedDoc.selectFirst("iframe[src]")?.attr("src")
                    if (!innerIframe.isNullOrBlank()) {
                        loadExtractor(innerIframe, subtitleCallback, callback)
                    }
                } catch (e: Exception) {}
            }
        }

        // 3. Method B: body class se term ID nikaalo
        val bodyClass = doc.selectFirst("body")?.attr("class") ?: ""
        val termMatch = Regex("(?:term|postid)-(\\d+)").find(bodyClass)?.groupValues?.get(1)
        if (termMatch.isNullOrBlank()) {
            // Fallback — body class me nahi mila
            return iframes.isNotEmpty()
        }

        // 4. Loop 0..10 — trdekho enumeration
        val mediaType = media.mediaType ?: 1
        for (i in 0..10) {
            val tryUrl = "$mainUrl/?trdekho=$i&trid=$termMatch&trtype=$mediaType"
            try {
                val page = app.get(tryUrl, headers = headers, allowRedirects = true)
                val iframe = page.document.selectFirst("iframe")?.attr("src")
                if (!iframe.isNullOrBlank()) {
                    loadExtractor(iframe, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                // swallow
            }
        }

        return true
    }

    data class Media(val url: String, val poster: String? = null, val mediaType: Int? = null)
}
