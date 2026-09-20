package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbUrl
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

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/movie-hindi/page/%d/" to "Movies",
        "$mainUrl/series-hindi/page/%d/" to "Series",
        "$mainUrl/genre/hindi-dub/page/%d/" to "Hindi Dub",
        "$mainUrl/genre/multi-audio/page/%d/" to "Multi Audio"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data.format(page)).document
        val home = document.select("article.item, div.movies-grid > a, div.item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.tagName() == "a") this else this.selectFirst("a") ?: return null
        val href = anchor.attr("href")
        if (href.isBlank()) return null

        val title = this.selectFirst("h2, h3, .entry-title, .title")?.text()
            ?: this.selectFirst("img")?.attr("alt")
            ?: anchor.text()
        if (title.isBlank()) return null

        val poster = this.selectFirst("img")?.let {
            it.attr("src").ifBlank { it.attr("data-src") }.ifBlank { it.attr("data-lazy-src") }
        }

        val cleanTitle = title
            .replace(Regex("(?i)\\(Hindi Dubbed\\)"), "")
            .replace(Regex("(?i)Download "), "")
            .trim()

        return newMovieSearchResponse(cleanTitle, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded&page=$page").document

        val results = document.select("article.item, div.result-item, div.movies-grid > a, div.item").mapNotNull {
            it.toSearchResult()
        }
        return newSearchResponseList(results)
    }

    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = fixUrl(url).substringBefore("#")
        val document = app.get(fixedUrl).document

        val rawTitle = document.selectFirst("h1.entry-title, h1, title")?.text()
            ?: return null
        val title = rawTitle
            .replace(Regex("(?i)\\(Hindi Dubbed\\)"), "")
            .replace(Regex("(?i)Download "), "")
            .replace(Regex("(?i)Watch Online.*"), "")
            .trim()

        val poster = document.selectFirst("div.poster img, meta[property=og:image], article img")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.attr("src").ifBlank { it.attr("data-src") }
        }

        val background = document.selectFirst("div.backdrop img, meta[property=og:image]")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.attr("src")
        } ?: poster

        val description = document.selectFirst("div.description, div.wp-content p, div[itemprop=description] p, p.synopsis")?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val imdbUrl = document.selectFirst("a[href*=\"imdb.com/title/\"]")?.attr("href")

        val genre = document.select("div.sgeneros a, div.genres a, span.genre a").map { it.text().trim() }
            .filter { it.isNotBlank() }

        val cast = document.select("div.person-list, div.cast-list, li[itemprop=actor]").map {
            it.text().trim()
        }.filter { it.isNotBlank() }

        val yearText = document.selectFirst("span.year, span.date, div.metaitems span")?.text() ?: ""
        val year = Regex("""\d{4}""").find(yearText)?.value?.toIntOrNull()

        val ratingText = document.selectFirst("span[itemprop=ratingValue], div.rating strong, span.dt_rating_vgs")?.text() ?: ""
        val rating = ratingText.toDoubleOrNull()

        val isSeries = document.selectFirst("div.episodios, div.seasons, div.episode-list, ul.episodios, div[class*=seasons]") != null
                || fixedUrl.contains("/series", true)

        if (isSeries) {
            val episodeList = document.select("div.episodios ul li, div.episode-list li, ul.episodios li, article.episodio")
            val episodes = episodeList.mapIndexedNotNull { index, ep ->
                val a = ep.selectFirst("a") ?: return@mapIndexedNotNull null
                val epUrl = a.attr("href")
                if (epUrl.isBlank()) return@mapIndexedNotNull null

                val epTitle = ep.selectFirst("h3, h2, .episodiotitle a, .title")?.text()?.trim()
                val epNum = Regex("""(\d+)""").find(ep.selectFirst(".numerando, .num, span")?.text() ?: "")?.groupValues?.get(1)?.toIntOrNull()
                    ?: (index + 1)
                val epImg = ep.selectFirst("img")?.attr("src").orEmpty()

                newEpisode(epUrl) {
                    this.name = epTitle ?: "Episode $epNum"
                    this.episode = epNum
                    this.posterUrl = epImg.ifBlank { null }
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = description
                this.tags = genre
                this.year = year
                this.score = rating?.let { Score.from10(it) }
                addActors(cast)
                if (imdbUrl != null) addImdbUrl(imdbUrl)
            }
        } else {
            val buttons = document.select("a.btn, a[href*=\"#\"], div.download-links a, p.download-links a")
                .filter { it.text().contains(Regex("(?i)(GDFlix|Hubcloud|NeoCDN|MirrorBot|VidCloud|HydraX|VidStream|SRuby|Omega)", RegexOption.IGNORE_CASE)) }
                .mapNotNull { btn ->
                    val link = btn.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    EpisodeLink(link)
                }

            return newMovieLoadResponse(title, url, TvType.Movie, buttons) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = description
                this.tags = genre
                this.year = year
                this.score = rating?.let { Score.from10(it) }
                addActors(cast)
                if (imdbUrl != null) addImdbUrl(imdbUrl)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sources = try {
            AppUtils.parseJson<ArrayList<EpisodeLink>>(data).map { it.source }
        } catch (e: Exception) {
            listOf(data)
        }

        sources.amap { source ->
            val cleanSource = source.substringBefore("#")
            loadExtractor(cleanSource, mainUrl, subtitleCallback, callback)
        }
        return true
    }

    data class EpisodeLink(
        val source: String
    )
}
