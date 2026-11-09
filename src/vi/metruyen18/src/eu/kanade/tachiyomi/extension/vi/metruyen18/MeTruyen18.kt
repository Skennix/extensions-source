package eu.kanade.tachiyomi.extension.vi.metruyen18

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebView
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Source
abstract class MeTruyen18 : KeiSource() {
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(5)
    }

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage = parseMangaList(webDocument(buildListUrl(page, popularSort).toString(), listReady))

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseMangaList(webDocument(buildListUrl(page, latestSort).toString(), listReady))

    // =============================== Search ===============================

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val sort = filters.firstInstanceOrNull<SortFilter>()?.toUriPart() ?: latestSort
        val status = filters.firstInstanceOrNull<StatusFilter>()?.toUriPart() ?: defaultStatus
        val searchType = filters.firstInstanceOrNull<SearchTypeFilter>()?.toUriPart() ?: "name"
        val selectedGenres = filters.firstInstanceOrNull<GenreFilter>()
            ?.state
            ?.filter { it.state }
            ?.joinToString(",") { it.id }

        val url = "$baseUrl/tim-kiem".toHttpUrl().newBuilder()
            .addQueryParameter("sort", sort)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("filter[status]", status)
            .apply {
                if (query.isNotBlank()) {
                    addQueryParameter("filter[$searchType]", query)
                }
                if (!selectedGenres.isNullOrEmpty()) {
                    addQueryParameter("filter[accept_genres]", selectedGenres)
                }
            }
            .build()

        return parseMangaList(webDocument(url.toString(), listReady))
    }

    private fun buildListUrl(page: Int, sort: String) = "$baseUrl/tim-kiem".toHttpUrl().newBuilder()
        .addQueryParameter("sort", sort)
        .addQueryParameter("filter[status]", defaultStatus)
        .addQueryParameter("page", page.toString())
        .build()

    private fun parseMangaList(document: Document): MangasPage {
        val mangas = document.select("div.manga-vertical").map { element ->
            SManga.create().apply {
                val titleElement = element.selectFirst("h3 a")!!
                title = titleElement.text()
                setUrlWithoutDomain(titleElement.absUrl("href"))

                val imageElement = element.selectFirst("div.cover-frame img")
                thumbnail_url = imageElement?.absUrl("src")
                    ?.ifEmpty { imageElement.absUrl("data-src") }
                    ?.ifEmpty { null }
            }
        }

        val hasNextPage = document.selectFirst("nav[aria-label=Pagination] a[aria-label=Next]") != null

        return MangasPage(mangas, hasNextPage)
    }

    // =============================== Details ==============================

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "truyen") return null

        val slug = url.pathSegments.getOrNull(1) ?: return null
        val manga = SManga.create().apply {
            setUrlWithoutDomain("/truyen/$slug")
        }

        return fetchMangaUpdate(manga, emptyList(), true, false).manga.apply {
            initialized = true
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = webDocument(getMangaUrl(manga), "#md-chapter-list")
        return SMangaUpdate(
            manga = parseMangaDetails(document, manga),
            chapters = parseChapterList(document),
        )
    }

    private fun parseMangaDetails(document: Document, manga: SManga): SManga = SManga.create().apply {
        setUrlWithoutDomain(manga.url)
        title = document.selectFirst("h1.md-title")!!.text()

        thumbnail_url = document.selectFirst(".md-hero img")?.absUrl("src")?.ifEmpty { null }

        author = document.selectFirst("a[href*=/tac-gia/]")?.text()
        genre = document.select(".md-hero dd a[href*=/the-loai/]")
            .joinToString { it.text() }
            .ifEmpty { null }

        status = parseStatus(document.selectFirst(".md-badges .md-badge")?.text())

        description = document.selectFirst(".md-synopsis p")?.wholeText()?.trim()?.ifEmpty { null }
    }

    private fun parseStatus(statusText: String?): Int = when {
        statusText?.contains("Đang tiến hành", ignoreCase = true) == true -> SManga.ONGOING
        statusText?.contains("Hoàn thành", ignoreCase = true) == true -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    // ============================== Chapters ==============================

    private fun parseChapterList(document: Document): List<SChapter> = document.select("#md-chapter-list a.md-ch").map { element ->
        SChapter.create().apply {
            setUrlWithoutDomain(element.absUrl("href"))
            name = element.selectFirst(".md-ch-title")!!.text()
            date_upload = parseChapterDate(element.selectFirst(".md-ch-meta > span")?.text())
        }
    }

    private fun parseChapterDate(dateStr: String?): Long = parseRelativeDate(dateStr)
        .takeIf { it != 0L }
        ?: dateFormat.tryParse(dateStr)

    private fun parseRelativeDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L

        val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"), Locale.ROOT)
        val number = numberRegex.find(dateStr)?.value?.toIntOrNull() ?: return 0L

        when {
            dateStr.contains("giây") -> calendar.add(Calendar.SECOND, -number)
            dateStr.contains("phút") -> calendar.add(Calendar.MINUTE, -number)
            dateStr.contains("giờ") -> calendar.add(Calendar.HOUR_OF_DAY, -number)
            dateStr.contains("ngày") -> calendar.add(Calendar.DAY_OF_MONTH, -number)
            dateStr.contains("tuần") -> calendar.add(Calendar.WEEK_OF_YEAR, -number)
            dateStr.contains("tháng") -> calendar.add(Calendar.MONTH, -number)
            dateStr.contains("năm") -> calendar.add(Calendar.YEAR, -number)
            else -> return 0L
        }

        return calendar.timeInMillis
    }

    // =============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        // Anh chuong khong nam trong HTML ma do script cua trang nap sau khi chay. Mo chuong trong WebView
        // (Chromium cua app) va lay anh bang HAI duong: (1) doc <img class=chapter-img> sau khi trang chay;
        // (2) bat cac request anh .../dcn/<id>/<so>.jpg ma trang tu gui. Duong nao on dinh truoc thi dung.
        var diag = "chua co du lieu"
        val net = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
        val seen = java.util.Collections.synchronizedList(ArrayList<String>())
        var jsOk = false
        val urls = try {
            runWebView<List<String>>(timeout = 60.seconds) {
                var last: List<String> = emptyList()
                var stable = 0
                var ticks = 0
                var netLast = 0
                var netStable = 0
                interceptRequest { request ->
                    val u = request.url.toString()
                    if (imageRequestRegex.containsMatchIn(u)) net.add(u)
                    seen.add(u.substringAfter("//").take(80))
                    null
                }
                onPageFinished { url ->
                    diag = "onPageFinished url=$url"
                    poll(interval = 800.milliseconds) {
                        ticks++
                        evaluateJs("'ok'") { jsOk = true }
                        val prefix = "ticks=$ticks net=${net.size} js=$jsOk reqs=${seen.size} last=${seen.toList().takeLast(8)}"
                        diag = prefix
                        if (net.size > 0 && net.size == netLast) netStable++ else netStable = 0
                        netLast = net.size
                        if (ticks >= 20 && netStable >= 5) {
                            resolve(net.toList().sortedBy { imageIndexRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0 })
                            return@poll
                        }
                        evaluateJs(diagJs) { value -> diag = prefix + " " + decodeJs(value) }
                        evaluateJs(readImagesJs) { value ->
                            val current = runCatching { decodeJs(value).parseAs<List<String>>() }.getOrDefault(emptyList())
                            if (current.isNotEmpty() && current == last) {
                                stable++
                                if (stable >= 3) resolve(current)
                            } else {
                                stable = 0
                                last = current
                            }
                        }
                    }
                }
                loadUrl(getChapterUrl(chapter))
            }
        } catch (e: Exception) {
            error("${e.message} | trang thai cuoi: $diag")
        }

        return urls.mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    // ============================== Filters ===============================

    override val supportsFilterFetching get() = true

    override suspend fun fetchFilterData(): JsonElement = webDocument("$baseUrl/tim-kiem", listReady)
        .select("button")
        .mapNotNull { element ->
            val id = genreIdRegex.matchEntire(element.attr("@click"))
                ?.groupValues
                ?.get(1)
                ?: return@mapNotNull null
            val name = element.text().takeIf { it.isNotEmpty() } ?: return@mapNotNull null

            GenreOption(name, id)
        }
        .distinctBy { it.id }
        .toJsonElement()

    override fun getFilterList(data: JsonElement?): FilterList = getFilters(data?.parseAs<List<GenreOption>>())

    // =============================== Related ==============================

    override val supportsRelatedMangas get() = true

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> {
        val document = webDocument(getMangaUrl(manga), "#md-chapter-list")
        val relatedSection = document.select("h3")
            .firstOrNull { it.text().equals("Truyện liên quan", ignoreCase = true) }
            ?.nextElementSibling()
            ?: return emptyList()

        return relatedSection.children().mapNotNull { card ->
            val link = card.selectFirst("a[href*=/truyen/].line-clamp-2") ?: return@mapNotNull null

            SManga.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                title = link.text()
                thumbnail_url = card.selectFirst("img")?.absUrl("src")
                    ?.ifEmpty { card.selectFirst("img")?.absUrl("data-src") }
                    ?.ifEmpty { null }
            }
        }.distinctBy { it.url }
    }

    private val latestSort = "-updated_at"
    private val popularSort = "-views"
    private val defaultStatus = "2,1"

    private val numberRegex = Regex("\\d+")
    private val genreIdRegex = Regex("toggleGenre\\('([^']+)'\\)")

    private val dateFormat by lazy {
        SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
        }
    }

    // Do 2026-10-26: Cloudflare chan client OkHttp cua app ("Cloudflare bypass currently disabled") tren cac
    // trang HTML, trong khi trinh duyet that qua duoc. Tai trang bang WebView (Chromium cua app) roi doc
    // HTML da render; doi den khi thay phan tu `ready` (hoac het 12 nhip thi lay trang hien co).
    private suspend fun webDocument(url: String, ready: String): Document {
        val html = runWebView(timeout = 60.seconds) {
            var started = false
            var ticks = 0
            onPageFinished {
                if (started) return@onPageFinished
                started = true
                poll(interval = 800.milliseconds) {
                    ticks++
                    val script = "(function(){ var ok = !!document.querySelector('$ready'); " +
                        "return (ok || $ticks >= 12) ? document.documentElement.outerHTML : ''; })()"
                    evaluateJs(script) { value ->
                        val page = decodeJs(value)
                        if (page.isNotEmpty()) resolve(page)
                    }
                }
            }
            loadUrl(url)
        }
        return Jsoup.parse(html, url)
    }

    // evaluateJs tra gia tri o dang JSON (chuoi co dau nhay) hoac chuoi tho tuy ban WebView: thu giai ma
    // JSON truoc, hong thi lay nguyen van. "null" => rong.
    private fun decodeJs(value: String): String {
        val decoded = runCatching { value.parseAs<String?>() }.getOrElse { value }
        return decoded.orEmpty()
    }

    private val listReady = "div.manga-vertical"

    private val diagJs = """
        document.readyState + ' | ' + location.href + ' | imgs=' + document.querySelectorAll('#chapter-content img').length +
            ' | chapter-img=' + document.querySelectorAll('#chapter-content img.chapter-img').length +
            ' | status=' + ((document.querySelector('[data-cipher-status]') || {}).innerText || '').replace(/\s+/g, ' ').slice(0, 80) +
            ' | title=' + document.title.slice(0, 40)
    """.trimIndent()

    private val imageRequestRegex = Regex("""/dcn/[^?#]+/\d+\.(jpg|jpeg|png|webp|avif)""")
    private val imageIndexRegex = Regex("""/(\d+)\.(?:jpg|jpeg|png|webp|avif)""")

    private val readImagesJs = """
        JSON.stringify(Array.from(document.querySelectorAll('#chapter-content img.chapter-img'))
            .map(function (i) { return i.getAttribute('data-src') || i.src; })
            .filter(function (u) { return u && u.indexOf('http') === 0; }))
    """.trimIndent()
}
