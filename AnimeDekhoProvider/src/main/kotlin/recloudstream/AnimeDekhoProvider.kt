package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeDekhoProvider : MainAPI() {
    override var mainUrl = "https://animedekho.app"
    override var name = "AnimeDekho"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    private val userAgent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    override val mainPage = mainPageOf(
        "$mainUrl/home/page/%d/" to "Home",
        "$mainUrl/category/anime/page/%d/" to "Anime",
        "$mainUrl/category/hindi-dub/page/%d/" to "Hindi Dub",
        "$mainUrl/category/cartoon/page/%d/" to "Cartoon",
        "$mainUrl/category/tamil/page/%d/" to "Tamil",
        "$mainUrl/category/telugu/page/%d/" to "Telugu"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data.format(page), headers = mapOf("User-Agent" to userAgent)).document
        val home = document.select("article.post, div.post.dfx.fcl.movies, article.post.dfx.fcl.movies").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a") ?: return null
        val href = anchor.attr("href")
        if (href.isBlank()) return null
        val title = selectFirst(".entry-title, h2, h3")?.text() ?: selectFirst("img")?.attr("alt") ?: anchor.text()
        if (title.isBlank()) return null
        val poster = selectFirst(".post-thumbnail img, img")?.let {
            it.attr("src").ifBlank { it.attr("data-src") }.ifBlank { it.attr("data-lazy-src") }
        }
        return newMovieSearchResponse(title.trim(), href, TvType.Movie) { this.posterUrl = poster }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = if (page == 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded"
        val document = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        val results = document.select("article.post, div.post.dfx.fcl.movies, article.post.dfx.fcl.movies").mapNotNull { it.toSearchResult() }
        return newSearchResponseList(results)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content") ?: document.selectFirst("div.poster img, .post-thumbnail img")?.attr("src")
        val plot = document.selectFirst("div[itemprop=description], .wp-content p, div.description, .entry-content p")?.text()?.trim()
        val genre = document.select("div.sgeneros a, .genres a, .entry-meta a").map { it.text().trim() }.filter { it.isNotBlank() && it.length < 30 }
        val yearText = document.selectFirst("span.year, .entry-meta .year")?.text() ?: ""
        val year = Regex("""\d{4}""").find(yearText)?.value?.toIntOrNull()

        val episodeRegex = Regex("""(?i)^S\d+-E\d+""")
        val episodeAnchors = document.select("a").filter { a -> episodeRegex.containsMatchIn(a.text().trim()) }

        if (episodeAnchors.isNotEmpty()) {
            val episodes = episodeAnchors.mapIndexed { idx, a ->
                val epUrl = a.attr("href").takeIf { it.isNotBlank() } ?: ""
                val epName = a.text().trim()
                val s = Regex("""(?i)S(\d+)-""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val e = Regex("""(?i)E(\d+)""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: (idx + 1)
                newEpisode(epUrl) { this.name = epName; this.season = s; this.episode = e }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster; this.plot = plot; this.tags = genre; this.year = year
            }
        } else {
            val links = document.select("a.button45, a.buttondl, .buttondl a, a[href*=dl1.php], a[href*=dl2.php]")
                .mapNotNull { a ->
                    val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val label = a.text().trim().ifBlank { "Download" }
                    EpisodeLink(href, label)
                }.distinctBy { it.url }
            return newMovieLoadResponse(title, url, TvType.Movie, links) {
                this.posterUrl = poster; this.plot = plot; this.tags = genre; this.year = year
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val links = try { AppUtils.parseJson<ArrayList<EpisodeLink>>(data) } catch (e: Exception) { listOf(EpisodeLink(data, "Link")) }
        links.amap { link -> resolveLink(link.url, link.name, subtitleCallback, callback) }
        return true
    }

    private suspend fun resolveLink(url: String, label: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val headers = mapOf("User-Agent" to userAgent, "Referer" to mainUrl)
        if (url.contains("dl1.php") || url.contains("dl2.php") || url.contains("dl.php")) {
            try {
                val resp = app.get(url, headers = headers, allowRedirects = true)
                val body = resp.text
                val iframeMatch = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(body)
                if (iframeMatch != null && iframeMatch.groupValues[1].isNotBlank()) {
                    loadExtractor(iframeMatch.groupValues[1], mainUrl, subtitleCallback, callback); return
                }
                val openMatch = Regex("""window\.open\(\s*["']([^"']+)["']""").find(body)
                if (openMatch != null && openMatch.groupValues[1].isNotBlank()) {
                    loadExtractor(openMatch.groupValues[1], mainUrl, subtitleCallback, callback); return
                }
                val videoMatch = Regex("""https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*""").find(body)
                if (videoMatch != null) {
                    callback.invoke(newExtractorLink("AnimeDekho", "AnimeDekho - $label", videoMatch.value, ExtractorLinkType.VIDEO) {
                        this.referer = mainUrl
                        this.quality = getQualityFromName(label)
                    }); return
                }
                loadExtractor(resp.url, mainUrl, subtitleCallback, callback)
            } catch (e: Exception) {
                loadExtractor(url, mainUrl, subtitleCallback, callback)
            }
        } else {
            loadExtractor(url, mainUrl, subtitleCallback, callback)
        }
    }

    data class EpisodeLink(val url: String, val name: String)
}
