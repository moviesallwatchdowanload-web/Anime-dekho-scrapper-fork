package recloudstream

import java.util.Base64
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

        // Series — has seasons
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

        // Movie OR Episode page — extract servers from ul.bx-lst (base64 data-src)
        val servers = document.select("ul.bx-lst li a, ul.aa-tbs li a").mapNotNull { a ->
            val encoded = a.attr("data-src").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val decoded = try {
                String(Base64.getDecoder().decode(encoded))
            } catch (e: Exception) {
                return@mapNotNull null
            }
            val name = a.selectFirst(".num")?.text()?.trim() ?: "Server"
            EpisodeLink(decoded, name)
        }.distinctBy { it.url }

        // Fallback — button45 links (dl1.php chain) for movies
        if (servers.isEmpty()) {
            val dlLinks = document.select("a.button45").mapNotNull { a ->
                val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val label = a.text().trim().ifBlank { "Download" }
                EpisodeLink(href, label)
            }.distinctBy { it.url }
            return newMovieLoadResponse(title, url, TvType.Movie, dlLinks) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genre
                this.year = year
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, servers) {
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
                resolveServerLink(link.url, link.name, subtitleCallback, callback)
            } catch (e: Exception) {
                // per-link error swallowed
            }
        }
        return true
    }

    private suspend fun resolveServerLink(
        url: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf("User-Agent" to userAgent, "Referer" to mainUrl)

        // Direct embed URL (VidStream case)
        if (url.contains("/embed/")) {
            try {
                val doc = app.get(url, headers = headers).document
                // Look for iframe/video/window.open inside
                val iframeSrc = doc.selectFirst("iframe[src]")?.attr("src")
                val videoSrc = Regex("""https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*""").find(doc.html())?.value
                val openSrc = Regex("""window\.open\(\s*["']([^"']+)["']""").find(doc.html())?.groupValues?.get(1)

                when {
                    !iframeSrc.isNullOrBlank() && !iframeSrc.startsWith("about:") -> {
                        loadExtractor(iframeSrc, mainUrl, subtitleCallback, callback)
                    }
                    !videoSrc.isNullOrBlank() -> {
                        callback.invoke(
                            newExtractorLink("AnimeDekho", "AnimeDekho - $label", videoSrc, ExtractorLinkType.VIDEO) {
                                this.referer = mainUrl
                                this.quality = getQualityFromName(label)
                            }
                        )
                    }
                    !openSrc.isNullOrBlank() -> {
                        loadExtractor(openSrc, mainUrl, subtitleCallback, callback)
                    }
                    else -> {
                        // fallback
                        loadExtractor(url, mainUrl, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                loadExtractor(url, mainUrl, subtitleCallback, callback)
            }
            return
        }

        // dl1.php chain
        if (url.contains("dl1.php") || url.contains("dl2.php") || url.contains("/dl.php") || url.contains("play.php")) {
            var currentUrl = url
            var depth = 0
            val visited = mutableSetOf<String>()

            while (depth < 6 && !visited.contains(currentUrl)) {
                visited.add(currentUrl)
                depth++

                if (currentUrl.contains("gdflix", true) || currentUrl.contains("hubcloud", true)) {
                    loadExtractor(currentUrl, mainUrl, subtitleCallback, callback)
                    return
                }

                val resp = try {
                    app.get(currentUrl, headers = headers, allowRedirects = false)
                } catch (e: Exception) {
                    loadExtractor(currentUrl, mainUrl, subtitleCallback, callback)
                    return
                }

                val location = resp.headers["location"] ?: resp.headers["Location"]
                if (!location.isNullOrBlank()) {
                    currentUrl = location
                    continue
                }

                val body = resp.text
                val iframeMatch = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(body)
                if (iframeMatch != null) {
                    val iframeSrc = iframeMatch.groupValues[1]
                    if (iframeSrc.isNotBlank() && !iframeSrc.startsWith("about:")) {
                        loadExtractor(iframeSrc, mainUrl, subtitleCallback, callback)
                        return
                    }
                }
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
                loadExtractor(currentUrl, mainUrl, subtitleCallback, callback)
                return
            }
            loadExtractor(currentUrl, mainUrl, subtitleCallback, callback)
            return
        }

        // Query-based URLs (MyCloud etc: https://animedekho.app/?trdekho=...)
        if (url.contains("animedekho.app")) {
            try {
                val resp = app.get(url, headers = headers, allowRedirects = true)
                val body = resp.text
                val iframeMatch = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(body)
                if (iframeMatch != null && iframeMatch.groupValues[1].isNotBlank()) {
                    loadExtractor(iframeMatch.groupValues[1], mainUrl, subtitleCallback, callback)
                    return
                }
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
                loadExtractor(resp.url, mainUrl, subtitleCallback, callback)
            } catch (e: Exception) {
                loadExtractor(url, mainUrl, subtitleCallback, callback)
            }
            return
        }

        // Default
        loadExtractor(url, mainUrl, subtitleCallback, callback)
    }

    data class EpisodeLink(val url: String, val name: String)
}
