package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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
        val home = document.select("article.post").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a.lnk-blk") ?: selectFirst("a") ?: return null
        val href = anchor.attr("href").ifBlank { selectFirst(".watch-btn")?.attr("href") ?: "" }
        if (href.isBlank()) return null
        val title = selectFirst(".entry-title")?.text()?.trim() ?: selectFirst("img")?.attr("alt") ?: return null
        if (title.isBlank()) return null
        val poster = selectFirst(".post-thumbnail img, img")?.let {
            it.attr("src").ifBlank { it.attr("data-src") }
        }
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = if (page == 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded"
        val document = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        val results = document.select("article.post").mapNotNull { it.toSearchResult() }
        return newSearchResponseList(results)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("div.poster img, .post-thumbnail img")?.attr("src")
        val plot = document.selectFirst("div[itemprop=description], .wp-content p, .entry-content p")?.text()?.trim()
        val genre = document.select("ul.details-lst li span a").map { it.text().trim() }.filter { it.isNotBlank() }
        val year = Regex("""\d{4}""").find(document.selectFirst(".entry-meta .year, span.year")?.text() ?: "")?.value?.toIntOrNull()

        // Series detection
        val seasonsContainer = document.select("div.seasons-bx")
        if (seasonsContainer.isNotEmpty()) {
            val episodes = mutableListOf<Episode>()
            seasonsContainer.forEach { seasonBox ->
                val seasonText = seasonBox.selectFirst(".seasons-tt p span")?.text()?.trim() ?: "1"
                val seasonNum = seasonText.toIntOrNull() ?: if (seasonText.equals("Special", true)) 0 else 1
                seasonBox.select("ul.seasons-lst li").forEach { li ->
                    val epAnchor = li.selectFirst("a.btn.sm.rnd, a") ?: return@forEach
                    val epUrl = epAnchor.attr("href").takeIf { it.isNotBlank() } ?: return@forEach
                    val epTitle = li.selectFirst("h3.title")?.text()?.trim() ?: ""
                    val epNum = Regex("""(?i)E(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: (episodes.size + 1)
                    val epPoster = li.selectFirst("figure img")?.attr("src")
                    episodes.add(newEpisode(epUrl) {
                        this.name = epTitle
                        this.season = seasonNum
                        this.episode = epNum
                        this.posterUrl = epPoster
                    })
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genre
                this.year = year
            }
        }

        // Movie / Episode page — button45 links
        val links = document.select("a.button45").mapNotNull { a ->
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val label = a.text().trim().ifBlank { "Download" }
            EpisodeLink(href, label)
        }.distinctBy { it.url }

        return newMovieLoadResponse(title, url, TvType.Movie, links) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genre
            this.year = year
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val links = try {
            AppUtils.parseJson<ArrayList<EpisodeLink>>(data)
        } catch (e: Exception) {
            listOf(EpisodeLink(data, "Link"))
        }

        links.amap { link ->
            try {
                resolveChain(link.url, link.name, subtitleCallback, callback)
            } catch (e: Exception) {
                // swallow per-link error
            }
        }
        return true
    }

    private suspend fun resolveChain(
        startUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to mainUrl
        )

        var currentUrl = startUrl
        var depth = 0
        val visited = mutableSetOf<String>()

        while (depth < 6 && !visited.contains(currentUrl)) {
            visited.add(currentUrl)
            depth++

            // If URL is already a known extractor target → hand off
            if (currentUrl.contains("gdflix", true) ||
                currentUrl.contains("hubcloud", true) ||
                currentUrl.contains("drivebot.sbs", true) ||
                currentUrl.contains("filesgram", true)) {
                loadExtractor(currentUrl, mainUrl, subtitleCallback, callback)
                return
            }

            val resp = try {
                app.get(currentUrl, headers = headers, allowRedirects = false)
            } catch (e: Exception) {
                // try as extractor on last URL
                loadExtractor(currentUrl, mainUrl, subtitleCallback, callback)
                return
            }

            val location = resp.headers["location"] ?: resp.headers["Location"]

            if (!location.isNullOrBlank()) {
                currentUrl = location
                continue
            }

            // No redirect — final page. Extract iframe / window.open / direct video
            val body = resp.text

            // 1. iframe
            val iframeMatch = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(body)
            if (iframeMatch != null) {
                val iframeSrc = iframeMatch.groupValues[1]
                if (iframeSrc.isNotBlank() && !iframeSrc.startsWith("about:")) {
                    loadExtractor(iframeSrc, mainUrl, subtitleCallback, callback)
                    return
                }
            }

            // 2. window.open
            val openMatch = Regex("""window\.open\(\s*["']([^"']+)["']""").find(body)
            if (openMatch != null && openMatch.groupValues[1].isNotBlank()) {
                val openUrl = openMatch.groupValues[1]
                if (openUrl.contains("gdflix", true) || openUrl.contains("hubcloud", true)) {
                    loadExtractor(openUrl, mainUrl, subtitleCallback, callback)
                    return
                }
                currentUrl = openUrl
                continue
            }

            // 3. direct video
            val videoMatch = Regex("""https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*""").find(body)
            if (videoMatch != null) {
                callback.invoke(
                    newExtractorLink("AnimeDekho", "AnimeDekho - $label", videoMatch.value, ExtractorLinkType.VIDEO) {
                        this.referer = mainUrl
                        this.quality = getQualityFromName(label)
                    }
                )
                return
            }

            // Fallback: try extractor on current URL
            loadExtractor(currentUrl, mainUrl, subtitleCallback, callback)
            return
        }

        // Depth exceeded
        loadExtractor(currentUrl, mainUrl, subtitleCallback, callback)
    }

    data class EpisodeLink(val url: String, val name: String)
}
