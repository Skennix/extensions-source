package eu.kanade.tachiyomi.extension.vi.truyengg

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import kotlinx.serialization.json.JsonElement
import okhttp3.CacheControl
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class TruyenGG : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = apply {
        rateLimit(1, 2.seconds) { it.host == baseUrl.toHttpUrl().host }
    }

    private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/top-ngay" + if (page > 1) "/trang-$page.html" else ".html"

        return parseMangaPage(client.get(url))
    }

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/truyen-moi-cap-nhat" + if (page > 1) "/trang-$page.html" else ""

        return parseMangaPage(client.get(url))
    }

    // =============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        // Tim theo ten: trang /tim-kiem.html?q= tra than rong cho client khong chay JS, nen dung
        // dung endpoint autocomplete ma o tim kiem cua site goi (POST search=<tu khoa>, mot trang).
        if (query.isNotBlank()) {
            val body = FormBody.Builder().add("search", query).add("type", "0").build()
            val document = client.post("$baseUrl/frontend/search/search", body).asJsoup()
            val mangas = document.select("li > a[href*=/truyen-tranh/]").map { element ->
                SManga.create().apply {
                    setUrlWithoutDomain(element.absUrl("href"))
                    title = element.selectFirst("p.name")!!.text()
                    thumbnail_url = element.selectFirst(".search_avatar img")?.absUrl("src")
                }
            }
            return MangasPage(mangas, false)
        }

        val endpoint = "tim-kiem-nang-cao"
        val url = ("$baseUrl/$endpoint" + if (page > 1) "/trang-$page.html" else ".html").toHttpUrl().newBuilder().apply {
            run {
                filters.forEach { filter ->
                    when (filter) {
                        is CountryFilter -> addQueryParameter("country", filter.values[filter.state].id)
                        is StatusFilter -> addQueryParameter("status", filter.values[filter.state].id)
                        is ChapterCountFilter -> addQueryParameter("minchapter", filter.values[filter.state].id)
                        is SortByFilter -> filter.state?.let {
                            addQueryParameter("sort", (it.index * 2 + if (it.ascending) 1 else 0).toString())
                        }
                        is GenreList -> {
                            addQueryParameter(
                                "category",
                                filter.state.filter { it.state == Filter.TriState.STATE_INCLUDE }
                                    .joinToString(",") { it.id },
                            )
                            addQueryParameter(
                                "notcategory",
                                filter.state.filter { it.state == Filter.TriState.STATE_EXCLUDE }
                                    .joinToString(",") { it.id },
                            )
                        }
                        else -> {}
                    }
                }
            }
        }.build()

        return parseMangaPage(client.get(url))
    }

    private fun parseMangaPage(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("ul.list_grid > li:has(.book_name)").map { element ->
            SManga.create().apply {
                val link = element.selectFirst(".book_name a")!!
                setUrlWithoutDomain(link.absUrl("href"))
                title = link.text()
                thumbnail_url = element.selectFirst(".book_avatar img")?.absUrl("src")
            }
        }
        // Phan trang: o hien tai la <p class="active"> trong <a href="javascript:void(0)">, cac trang khac la link that.
        val current = document.selectFirst(".page_redirect p.active")?.text()?.toIntOrNull()
        val hasNextPage = current != null &&
            document.select(".page_redirect a[href*=trang-]").any { Regex("trang-(\\d+)").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull()?.let { n -> n > current } == true }
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host == baseUrl.toHttpUrl().host) {
            client.get(url).use { response ->
                return parseMangaDetails(response.asJsoup())
            }
        }
        return null
    }

    // =========================== Manga Details ============================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()

        return SMangaUpdate(
            parseMangaDetails(document),
            parseChapterList(document),
        )
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        title = document.select(".book_other h1[itemprop=name]").text()
        author = document.selectFirst(".book-counts__label:contains(Tác giả) + .book-counts__value")?.text()
        genre = document.select(".book-genres a").joinToString { it.text() }
        description = document.selectFirst(".story-detail-info")?.wholeText()?.trim()
        thumbnail_url = document.selectFirst(".book_other")?.parent()?.selectFirst(".book_avatar img")?.absUrl("src")
        status = parseStatus(document.select(".book-counts__label:contains(Tình trạng) + .book-counts__value").text())
    }

    private fun parseStatus(status: String?): Int {
        val ongoingWords = listOf("Đang Cập Nhật", "Đang Tiến Hành", "Còn tiếp", "Đang ra")
        val completedWords = listOf("Hoàn Thành", "Đã Hoàn Thành", "Hoàn")
        val hiatusWords = listOf("Tạm ngưng", "Tạm hoãn", "Bị drop")
        return when {
            status == null -> SManga.UNKNOWN
            ongoingWords.any { status.contains(it, ignoreCase = true) } -> SManga.ONGOING
            completedWords.any { status.contains(it, ignoreCase = true) } -> SManga.COMPLETED
            hiatusWords.any { status.contains(it, ignoreCase = true) } -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }

    private fun parseChapterList(document: Document): List<SChapter> = document.select(".works-chapter-list .works-chapter-item").map { element ->
        SChapter.create().apply {
            setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
            name = element.select("a").text()
            date_upload = dateFormat.tryParse(element.selectFirst(".time-chap")?.text()?.trim())
        }
    }

    private fun DateTimeFormatter.tryParse(date: String?): Long = runCatching {
        LocalDate.parse(date!!, this)
            .atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh"))
            .toInstant()
            .toEpochMilli()
    }.getOrDefault(0L)

    // =============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        // Do 2026-10-26: voi request mac dinh cua OkHttp, trang doc tra HTTP 200 nhung THAN RONG (curl lay duoc
        // du anh). Them Accept/Accept-Language + FORCE_NETWORK thi co anh. Chua tach duoc yeu to nao la nguyen
        // nhan that, nen giu ca hai.
        val headers = headersBuilder()
            .set("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            .set("Accept-Language", "vi-VN,vi;q=0.9,en;q=0.8")
            .build()
        client.get(getChapterUrl(chapter), headers, CacheControl.FORCE_NETWORK).use { response ->
            val document = response.asJsoup()
            return document.select(".chapter_content img.lazy")
                .mapIndexed { idx, it ->
                    Page(idx, imageUrl = it.absUrl("data-original").ifEmpty { it.absUrl("src") })
                }
        }
    }

    // ============================== Filters ===============================

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("Không dùng chung với tìm kiếm bằng tên"),
        CountryFilter(),
        StatusFilter(),
        ChapterCountFilter(),
        SortByFilter(),
        GenreList(getGenreList()),
    )
}
