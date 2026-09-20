package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeDekhoProvider : MainAPI() {
    override var mainUrl = "https://animedekho.app"
    override var name = "AnimeDekho"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    private val userAgent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    override val mainPage = mainPageOf(
        "$mainUrl/home/page/%d/" to "Home",
        "$mainUrl/category/anime/page/%d/" to "Anime",
        "$mainUrl/category/hindi-dub/page/%d/" to "Hindi Dub",
        "$mainUrl/category/cartoon/page/%d/" to "Cartoon",
        "$mainUrl/category/tamil/page/%d/" to "Tamil",
        "$mainUrl/category/telugu/page/%d/" to "Telugu"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(
            request.data.format(page),
            headers = mapOf("User-Agent" to userAgent)
        ).document
        val home = document.select("article.post.dfx.fcl.movies, div.post.dfx.fcl.movies, article.post")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a") ?: return null
        val href = anchor.attr("href")
        if (href.isBlank()) return null

        val title = selectFirst(".entry-title, h2, h3")?.text()
            ?: selectFirst("img")?.attr("alt")
            ?: anchor.text()
        if (title.isBlank()) return null

        val poster = selectFirst(".post-thumbnail img, img")?.let {
            it.attr("src").ifBlank { it.attr("data-src") }
                .ifBlank { it.attr("data-lazy-src") }
        }

        return newMovieSearchResponse(title.trim(), href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = if (page == 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded"
        val document = app.get(url, headers = mapOf("User-Agent" to userAgent)).document

        val results = document.select("article.post.dfx.fcl.movies, div.post.dfx.fcl.movies, article.post")
            .mapNotNull { it.toSearchResult() }
        return newSearchResponseList(results)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = mapOf("User-Agent" to userAgent)).document

        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim()
            ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("div.poster img, .post-thumbnail img")?.attr("src")
        val plot = document.selectFirst("div.description p, .wp-content p, [itemprop=description]")?.text()?.trim()

        val genre = document.select("div.sgeneros a, .genres a").map { it.text().trim() }.filter { it.isNotBlank() }
        val cast = document.select("[itemprop=actor] .name, .cast-list li").map { it.text().trim() }.filter { it.isNotBlank() }
        val yearText = document.selectFirst("span.year, [itemprop=datePublished]")?.text() ?: ""
        val year = Regex("""\d{4}""").find(yearText)?.value?.toIntOrNull()
        val rating = document.selectFirst("[itemprop=ratingValue]")?.text()?.toDoubleOrNull()

        // Detect series
        val isSeries = document.selectFirst("div.episodios, ul.episodios, div.episode-list") != null
                || url.contains("/series", true) || url.contains("/episode", true)

        if (isSeries) {
            val epList = document.select("div.episodios li, ul.episodios li, .episode-list li, article.episodio")
            val episodes = epList.mapIndexedNotNull { idx, ep ->
                val a = ep.selectFirst("a") ?: return@mapIndexedNotNull null
                val epUrl = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                val epName = ep.selectFirst(".episodiotitle a, h3, .title")?.text()?.trim() ?: "Episode ${idx + 1}"
                val epNum = Regex("""(\d+)""").find(ep.selectFirst(".numerando, .num")?.text() ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: (idx + 1)
                newEpisode(epUrl) {
                    this.name = epName
                    this.episode = epNum
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genre
                this.year = year
                this.score = rating?.let { Score.from10(it) }
                addActors(cast)
            }
        } else {
            // Movie - extract dl1.php links
            val links = document.select("a.button45, a[href*=dl1.php], a[href*=dl2.php]")
                .mapNotNull { a ->
                    val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val label = a.text().trim().ifBlank { a.selectFirst("span")?.text()?.trim() ?: "Download" }
                    EpisodeLink(href, label)
                }
                .distinctBy { it.url }

            return newMovieLoadResponse(title, url, TvType.Movie, links) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genre
                this.year = year
                this.score = rating?.let { Score.from10(it) }
                addActors(cast)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = try {
            AppUtils.parseJson<ArrayList<EpisodeLink>>(data)
        } catch (e: Exception) {
            listOf(EpisodeLink(data, "Link"))
        }

        links.amap { link ->
            resolveDownload(link.url, link.name, subtitleCallback, callback)
        }
        return true
    }

    private suspend fun resolveDownload(
        url: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf("User-Agent" to userAgent, "Referer" to mainUrl)

        // Follow redirect chain
        val first = app.get(url, headers = headers, allowRedirects = false)
        val loc1 = first.headers["location"] ?: first.headers["Location"]
        val next = loc1 ?: url

        val second = app.get(next, headers = headers, allowRedirects = false)
        val loc2 = second.headers["location"] ?: second.headers["Location"]
        val third = loc2 ?: next

        val finalDoc = app.get(third, headers = headers).document

        // Extract embed iframe or redirect target
        finalDoc.selectFirst("iframe[src]")?.let { iframe ->
            val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@let
            loadExtractor(src, mainUrl, subtitleCallback, callback)
            return
        }

        // Extract window.open JS
        val scripts = finalDoc.select("script").map { it.data() }.joinToString("\n")
        val openMatch = Regex("""window\.open\(\s*["']([^"']+)["']""").find(scripts)
        openMatch?.groupValues?.get(1)?.let { openUrl ->
            loadExtractor(openUrl, mainUrl, subtitleCallback, callback)
            return
        }

        // Direct video URL fallback
        val direct = Regex("""https?://[^\s"'<>]+(?:\.m3u8|\.mp4)[^\s"'<>]*""").find(scripts)?.value
        if (direct != null) {
            callback.invoke(
                newExtractorLink("AnimeDekho", "AnimeDekho - $label", direct, ExtractorLinkType.VIDEO) {
                    this.referer = mainUrl
                    this.quality = getQualityFromName(label)
                }
            )
        }
    }

    data class EpisodeLink(
        val url: String,
        val name: String
    )
}
