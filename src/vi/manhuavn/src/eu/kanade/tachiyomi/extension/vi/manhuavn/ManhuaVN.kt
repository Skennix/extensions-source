package eu.kanade.tachiyomi.extension.vi.manhuavn

import eu.kanade.tachiyomi.network.GET
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
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.seconds

/**
 * Nguon ManhuaVN — ASP.NET WebForms tu viet (endpoint `.asmx`), dung sau Cloudflare.
 * Khong thuoc ho NetTruyen/Madara/wpcomics nen khong tai dung duoc theme nao.
 *
 * Toan bo hinh dang duoi day do truc tiep ngay 2026-09-03; xem `docs/BACKLOG.md` muc (85).
 */
@Source
abstract class ManhuaVN : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = apply {
        rateLimit(2, 1.seconds) { it.host == baseUrl.toHttpUrl().host }
    }

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage =
        parseMangaPage(client.get(listUrl(page, status = "0", sort = SORT_HOT)), LIST_PAGE_SIZE)

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        parseMangaPage(client.get(listUrl(page, status = "0", sort = SORT_LATEST)), LIST_PAGE_SIZE)

    // =============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val status = filters.firstInstanceOrNull<StatusFilter>()?.selected ?: "0"
        val sort = filters.firstInstanceOrNull<SortFilter>()?.selected ?: SORT_LATEST
        val genre = filters.firstInstanceOrNull<GenreFilter>()?.selected.orEmpty()

        return when {
            // Duong tim kiem khong nhan status/sort/the loai — no la mot route rieng.
            query.isNotBlank() ->
                parseMangaPage(client.get("$baseUrl/${query.toSearchKeyword()}/P$page/tim-kiem.html"), SEARCH_PAGE_SIZE)

            genre.isNotEmpty() ->
                parseMangaPage(client.get("$baseUrl/the-loai/P$page/$genre.html?status=$status&sort=$sort"), LIST_PAGE_SIZE)

            else ->
                parseMangaPage(client.get(listUrl(page, status, sort)), LIST_PAGE_SIZE)
        }
    }

    private fun listUrl(page: Int, status: String, sort: String) =
        "$baseUrl/danhsach/P$page/index.html?status=$status&sort=$sort"

    /**
     * Site ma hoa tu khoa vao duong dan chu khong vao query string.
     *
     * THU TU HAI PHEP THAY KHONG DUOC DAO: doi `-` thanh `_` TRUOC, roi moi doi khoang
     * trang thanh `-`. Lam nguoc lai thi nhung dau `-` vua sinh ra tu khoang trang se bi
     * phep thu hai nuot thanh `_`, va tu khoa hong ma van tra ve 200 kem 0 ket qua.
     */
    private fun String.toSearchKeyword(): String = trim()
        .replace("-", "_")
        .replace(WHITESPACE_REGEX, "-")

    private fun parseMangaPage(response: Response, pageSize: Int): MangasPage {
        val document = response.asJsoup()

        val entries = document.select("ul.lst_story > li.story_item").mapNotNull { element ->
            val anchor = element.selectFirst("a.story_title") ?: return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(anchor.absUrl("href"))
                title = anchor.text()
                thumbnail_url = element.thumbnailUrl()
            }
        }

        // Trang danh sach va trang the loai co pager; trang TIM KIEM khong in pager nao
        // ke ca khi con trang sau (do 2026-09-03: P1/P2/P3 deu day 30 muc, giao nhau 0,
        // P99 tra 200 kem 0 muc). Nen chi suy ra tu so luong khi that su khong co pager.
        val pager = document.selectFirst("nav.pagerhamtruyen")
        val hasNextPage = if (pager != null) {
            pager.selectFirst("a:has(span.glyphicon-chevron-right)") != null
        } else {
            entries.size >= pageSize
        }

        return MangasPage(entries, hasNextPage)
    }

    /**
     * Anh bia cua the truyen nam trong `style="background-image:url(...)"`, KHONG phai
     * `<img src>` — selector `img` tra rong tren trang danh sach. Khoi "Co The Ban Chua
     * Xem" tren trang chi tiet lai dung `<img class="lazy" data-original>`, nen do ca hai.
     */
    private fun Element.thumbnailUrl(): String? {
        selectFirst("[style*=background-image]")?.let { holder ->
            BACKGROUND_IMAGE_REGEX.find(holder.attr("style"))
                ?.groupValues?.get(1)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { return it }
        }

        return selectFirst("img")?.let { img ->
            img.absUrl("data-original")
                .ifEmpty { img.absUrl("src") }
                .takeIf { it.isNotEmpty() && !it.endsWith(LOADING_PLACEHOLDER) }
        }
    }

    // =========================== Manga Details ============================

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null

        val path = url.encodedPath.takeIf { it.endsWith("-0.html") } ?: return null

        return parseMangaDetails(client.get("$baseUrl$path").asJsoup()).apply {
            setUrlWithoutDomain(path)
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()

        return SMangaUpdate(
            parseMangaDetails(document),
            fetchChapterList(document),
        )
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1.title")!!.text()

        // Tren trang CHI TIET anh bia lai la <img src> that — khac trang danh sach.
        thumbnail_url = document.selectFirst("div.wrap-content-image img")?.absUrl("src")

        genre = document.select("ul.list-info a[href*=/the-loai/]").joinToString { it.text() }
        author = document.infoValue("Tác Giả")?.takeUnless { it.equals(UPDATING, ignoreCase = true) }
        status = parseStatus(document.infoValue("Tình Trạng"))
        description = document.parseDescription()
    }

    /** Doc gia tri cua mot dong trong `ul.list-info` theo nhan, vd "Tác Giả : Nguyen Van A". */
    private fun Document.infoValue(label: String): String? = select("ul.list-info > li")
        .firstOrNull { it.text().contains(label, ignoreCase = true) }
        ?.text()
        ?.substringAfter(":", "")
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    /**
     * Phan gioi thieu khong co class rieng: no la dong `li` duy nhat trong `ul.list-info`
     * co chua the `h2`. Bo `h2` di roi doi `<br>` thanh xuong dong.
     */
    private fun Document.parseDescription(): String? {
        val container = selectFirst("ul.list-info > li:has(h2)")?.clone() ?: return null

        container.select("h2").remove()
        container.select("br").before(LINE_BREAK_MARKER)

        return container.text()
            .replace(LINE_BREAK_MARKER, "\n")
            .lines()
            .joinToString("\n") { it.trim() }
            .trim()
            .takeIf(String::isNotEmpty)
    }

    private fun parseStatus(status: String?): Int = when {
        status == null -> SManga.UNKNOWN
        listOf("Đang tiến hành", "Đang cập nhật", "Đang ra").any { status.contains(it, ignoreCase = true) } -> SManga.ONGOING
        listOf("Hoàn thành", "Full").any { status.contains(it, ignoreCase = true) } -> SManga.COMPLETED
        listOf("Tạm ngưng", "Tạm dừng", "Tạm hoãn").any { status.contains(it, ignoreCase = true) } -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    // ============================== Chapters ==============================

    @Serializable
    private class ChapterListDto(val d: String)

    /**
     * BAY LON NHAT CUA NGUON NAY.
     *
     * HTML trang chi tiet chi in ~20 chuong dau; bo "Pham Nhan Tu Tien Truyen" co 407
     * chuong nhung trang chi tiet chi mang 20 (do 2026-09-03). Doc thang HTML mat ~95%
     * chuong va mat IM LANG — 20 chuong van trong nhu mot danh sach hop le.
     *
     * Danh sach du nam sau mot lan POST toi `Service.asmx`, tra ve `{"d":"<html>"}` gom
     * du 407 muc, khong phan trang.
     *
     * Vi vay: co `ssid` thi BAT BUOC di duong API va de loi noi len neu API hong. Khong
     * bao gio lang le lui ve HTML khi da biet co API — do dung la kieu hong im lang tren.
     */
    private suspend fun fetchChapterList(document: Document): List<SChapter> {
        val ssid = document.extractStoryId()

        val chapterListElement = if (ssid != null) {
            val payload = """{"ssid":"$ssid"}""".toRequestBody(JSON_MEDIA_TYPE)
            val apiHeaders = headers.newBuilder()
                .set("Content-Type", "application/json; charset=utf-8")
                .set("X-Requested-With", "XMLHttpRequest")
                .build()

            val html = client.post("$baseUrl/Service.asmx/getListChapterOfStory", apiHeaders, payload)
                .parseAs<ChapterListDto>()
                .d

            Jsoup.parseBodyFragment(html, baseUrl)
        } else {
            // Khong trich duoc id thi danh doc phan HTML co san — thieu chuong, nhung
            // van hon la tra ve rong.
            document
        }

        return chapterListElement.select("li.chap-item > a").map { anchor ->
            SChapter.create().apply {
                setUrlWithoutDomain(anchor.absUrl("href"))
                name = anchor.text().trim()
                chapter_number = CHAPTER_NUMBER_REGEX.find(anchor.attr("href"))
                    ?.groupValues?.get(1)
                    ?.toFloatOrNull()
                    ?: -1f
                // Site khong in ngay dang o bat cu dau nao trong danh sach chuong,
                // ke ca ban day tra ve tu `.asmx` — de 0 thay vi doan.
                date_upload = 0L
            }
        }
    }

    /**
     * Id cua bo truyen la mot ObjectId 24 hex. No xuat hien o hai cho tren trang chi
     * tiet; do ca hai vi mot cho co the vang tuy bo:
     *  - `loadfullchuong($(this),'<id>')` o nut "Xem them"
     *  - `setuprate('<id>', 4.8)` o khoi danh gia
     */
    private fun Document.extractStoryId(): String? {
        val html = html()

        return LOAD_CHAPTERS_REGEX.find(html)?.groupValues?.get(1)
            ?: SETUP_RATE_REGEX.find(html)?.groupValues?.get(1)
    }

    // =============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter), CacheControl.FORCE_NETWORK).asJsoup()

        // Chuong moi thuong bi khoa "chi doc duoc tren app", va khi do trang van tra 200
        // kem 0 anh. Phai bao loi ro chu khong tra danh sach rong.
        if (document.selectFirst("div.vip-lock") != null) {
            throw Exception("Chương này bị nguồn khoá, chỉ đọc được trên app của họ")
        }

        // Hai bay o day:
        //  - URL that nam o `data-original`; `src` chi la loading.gif
        //  - moi trang chuong con kem ~10 anh bia goi y tu admin.<domain>, nen phai
        //    gioi han trong `div.page-chapter` VA doi thuoc tinh `data-index`
        val pages = document.select("div.page-chapter img[data-index]").mapNotNull { element ->
            element.absUrl("data-original")
                .ifEmpty { element.absUrl("src") }
                .takeIf { it.isNotEmpty() && !it.endsWith(LOADING_PLACEHOLDER) }
        }

        if (pages.isEmpty()) {
            throw Exception("Không tìm thấy ảnh nào trong chương này")
        }

        return pages.mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    /**
     * CDN anh chan theo `Referer`: thieu header thi tra 403 kem dung 70 byte HTML
     * `<meta Refresh>` — tuc mot "loi" trong nhu anh hong chu khong trong nhu loi mang.
     *
     * Do 2026-09-03, co lap tung yeu to: khong header 403 · chi User-Agent 403 ·
     * `Referer: google.com` 403 · chi rieng `Referer` dung domain nguon 200.
     * User-Agent KHONG anh huong.
     *
     * Dat Referer cho MOI anh thay vi khop mot domain CDN, vi hai le do duoc:
     *  - so hieu host doi theo chuong (`img02`, `img06`, ... `.g5img.top`)
     *  - co chuong dung han host khac (chuong 50 cua bo do duoc tra ve `bp.blogspot.com`),
     *    va host do nhan Referer nay ma van tra 200, nen dat thua khong hai gi.
     */
    override fun imageRequest(page: Page): Request {
        val imageHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .build()

        return GET(page.imageUrl!!, imageHeaders)
    }

    // ============================== Filters ===============================

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("Bộ lọc bị bỏ qua khi tìm bằng tên"),
        GenreFilter(),
        StatusFilter(),
        SortFilter(),
    )

    companion object {
        private const val LIST_PAGE_SIZE = 50
        private const val SEARCH_PAGE_SIZE = 30

        private const val SORT_HOT = "1"
        private const val SORT_LATEST = "2"

        private const val UPDATING = "Đang cập nhật"
        private const val LOADING_PLACEHOLDER = "loading.gif"
        private const val LINE_BREAK_MARKER = "|BR|"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val WHITESPACE_REGEX = Regex("""\s+""")
        private val BACKGROUND_IMAGE_REGEX = Regex("""url\(\s*['"]?([^'")]+)""")
        private val LOAD_CHAPTERS_REGEX = Regex("""loadfullchuong\([^,]+,\s*'([a-f0-9]{24})'\)""")
        private val SETUP_RATE_REGEX = Regex("""setuprate\(\s*'([a-f0-9]{24})'""")
        private val CHAPTER_NUMBER_REGEX = Regex("""-chapter-(\d+(?:[.-]\d+)?)""")
    }
}
