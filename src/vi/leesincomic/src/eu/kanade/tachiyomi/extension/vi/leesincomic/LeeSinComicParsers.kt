package eu.kanade.tachiyomi.extension.vi.leesincomic

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// Hàm THUẦN cho leesincomic.com — không mạng, không suspend, không `Response`.
//
// Vì sao tách khỏi LeeSinComic: SManga.create() / SChapter.create() / Response.asJsoup() là
// "Stub!" ngoài app Mihon thật, nên gọi thẳng lớp nguồn trong unit test JVM sẽ nổ. Tách ra đây
// thì test chạy bằng JVM thường tính bằng mili-giây, và mangaFactory/chapterFactory cho phép
// tiêm cài đặt giả. Production vẫn dùng SManga.create() như 1800 chỗ khác của repo.
//
// (Dùng // chứ không /** */: ktlint `standard:kdoc` cấm KDoc treo ở mức tệp — nó phải gắn vào
// một khai báo. Đo 2026-09-08: `A dangling toplevel KDoc is not allowed`.)

// ── Hằng số neo: đổi site thì sửa ĐÚNG chỗ này ──────────────────────────────────────────────

/**
 * Cắt phạm vi cho lưới kết quả. Widget "Top ngày/tuần/tháng" (`ul.top_truyen li .detail`) mang
 * đúng mẫu URL bộ nhưng KHÔNG dùng lớp này — đo trên fixture 2026-09-08:
 *
 *   | phép                    | trang 1 | trang 3 | giao | trang CUỐI | tìm RỖNG |
 *   | CÓ cắt phạm vi          |      48 |      48 |    0 |          0 |        0 |
 *   | KHÔNG cắt (cả tài liệu) |      48 |      67 |   19 |         19 |       19 |
 *
 * Bỏ cắt phạm vi ⇒ mọi lượt duyệt trả lẫn cùng 19 mục widget, và **tìm kiếm không bao giờ ra rỗng**.
 */
internal const val CUM_KET_QUA = "div.li_truyen"

/** Cụm anh-em đồng dạng của danh sách chương trên trang bộ. */
internal const val CUM_CHUONG = "div.list-chapters div.chapter-item"

/**
 * MỌI thẻ `<img>` của trang chương — cố ý KHÔNG lọc sẵn `[data-src]` ở selector, để bộ lọc
 * [laAnhTruyen] phía dưới thực sự gánh việc. Đo `trang-chuong.html`: 36 thẻ `<img>`, 33 thẻ mang
 * `data-src` trỏ vào kho ảnh, 3 thẻ còn lại là logo + ảnh loading. Lọc sẵn ở selector thì phép phá
 * "bỏ bộ lọc kho ảnh" KHÔNG làm ca nào đỏ — guard xanh vì lý do sai.
 */
internal const val CUM_ANH = "img"

/**
 * `/truyen-tranh/<slug bộ>.html` — slug có thể chứa `+` và `!` (`19+-khoai-cam-toi-loi`), nên chỉ
 * cấm `/?#`. Neo theo MẪU URL chứ không theo `nth-child`: URL là hợp đồng công khai của site.
 */
internal val MAU_DUONG_BO = Regex("""/truyen-tranh/[^/?#]+\.html""")

/** `/truyen-tranh/<slug bộ>/<slug chương>.html` — slug chương TỰ DO, không có tiền tố `chap-`. */
internal val MAU_DUONG_CHUONG = Regex("""/truyen-tranh/[^/?#]+/[^/?#]+\.html""")

/**
 * Đoạn chung của MỌI host ảnh đã gặp: `tachserver.site` · `tachserver.online` · `leesincomic.com`.
 * Viết cứng một host là âm thầm mất trọn những bộ dùng host kia.
 */
internal const val DUONG_ANH = "/uploads/minh_hoa/"

private val DINH_DANG_NGAY = SimpleDateFormat("dd/MM/yyyy", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
}

// ── Danh sách bộ ────────────────────────────────────────────────────────────────────────────

/**
 * [allowEmpty] CHỈ dùng cho đường tìm kiếm CÓ TỪ KHOÁ: 0 kết quả khi user gõ từ khoá lạ là HỢP LỆ.
 * Mọi lời gọi khác giữ luật cứng "rỗng sau lọc thì ném lỗi kèm tên hàm + URL đã gọi".
 */
fun parseMangaList(
    html: String,
    functionName: String,
    url: String,
    allowEmpty: Boolean = false,
    mangaFactory: () -> SManga = { SManga.create() },
): MangasPage {
    val document = Jsoup.parse(html, url)

    val danhSach = document.select(CUM_KET_QUA).flatMap { o ->
        // Mỗi ô có HAI thẻ <a>: link bộ và link chương mới nhất. Lọc bằng MẪU ĐƯỜNG DẪN (bộ = 1
        // segment sau /truyen-tranh/, chương = 2), KHÔNG bằng vị trí anh em — nếu chỉ lấy thẻ <a>
        // ĐẦU TIÊN thì THỨ TỰ DOM đang gánh việc của mẫu URL, và mẫu URL nới lỏng lúc nào cũng
        // không ai biết (đo 2026-09-08: phép phá nới mẫu KHÔNG làm ca nào đỏ khi lấy thẻ đầu).
        o.linksTheoMau(MAU_DUONG_BO).map { link ->
            mangaFactory().apply {
                this.url = boDomain(link.absUrl("href"))
                title = o.selectFirst("div.name")?.text().orEmpty().ifBlank { link.text().trim() }
                thumbnail_url = o.selectFirst("img")?.absUrl("src")?.takeIf { it.isNotBlank() }
            }
        }
    }.filter { it.title.isNotBlank() }.distinctBy { it.url }

    if (danhSach.isEmpty()) {
        if (allowEmpty) return MangasPage(emptyList(), false)
        throw Exception(loiRong(functionName, url))
    }

    // Bộ đếm trang của site là CỬA SỔ TRƯỢT, không phản ánh trang đang xem: đo `?page=30` (đã hết
    // dữ liệu từ lâu) mà `div.page_redirect` vẫn in `9 10 11 12 13`. Nên chỉ dựa vào "trang này có
    // mục nào không" — trang hết dữ liệu trả về đúng 0 mục.
    return MangasPage(danhSach, true)
}

// ── Trang bộ ────────────────────────────────────────────────────────────────────────────────

fun parseMangaDetails(
    html: String,
    functionName: String,
    url: String,
    mangaFactory: () -> SManga = { SManga.create() },
): SManga {
    val document = Jsoup.parse(html, url)
    val khung = document.selectFirst("div.box_info") ?: throw Exception(loiRong(functionName, url))

    val ten = khung.selectFirst("div.box_info_right h1")?.text().orEmpty()
    if (ten.isBlank()) throw Exception(loiRong(functionName, url))

    return mangaFactory().apply {
        title = ten
        thumbnail_url = khung.selectFirst("div.box_info_left div.img img")?.absUrl("src")
        genre = khung.select("ul.list-tag-story li a").joinToString { it.text() }
        // `Tình trang` là chính tả của SITE, không phải lỗi gõ ở đây.
        status = khung.docThongTin("Tình trang").doiTrangThai()
        // Site KHÔNG có trường tác giả. Dòng gần nhất là `Nhóm dịch`, mà nhóm dịch KHÔNG phải tác
        // giả ⇒ để `author` rỗng và đưa nhóm dịch vào mô tả, đừng ghi dữ liệu sai vào metadata.
        description = buildString {
            khung.docThongTin("Tên Khác")?.let { appendLine("Tên khác: $it") }
            khung.docThongTin("Nhóm dịch")?.let { appendLine("Nhóm dịch: $it") }
            if (isNotEmpty()) appendLine()
            append(document.selectFirst("div.story-detail-info")?.wholeText().orEmpty().trim())
        }.trim()
    }
}

// ── Danh sách chương ────────────────────────────────────────────────────────────────────────

fun parseChapterList(
    html: String,
    functionName: String,
    url: String,
    chapterFactory: () -> SChapter = { SChapter.create() },
): List<SChapter> {
    val document = Jsoup.parse(html, url)
    val slugBo = slugBo(url)

    val danhSach = document.select(CUM_CHUONG).mapNotNull { o ->
        val link = o.linkTheoMau(MAU_DUONG_CHUONG) ?: return@mapNotNull null
        val duong = link.absUrl("href")
        // Neo slug BỘ: trang bộ cũng chứa link chương của bộ KHÁC (widget "truyện liên quan"),
        // và chúng khớp mẫu chương y hệt.
        if (slugBo != null && slugBo(duong) != slugBo) return@mapNotNull null

        val tenChuong = link.ownText().trim().ifBlank { link.attr("title").trim() }
        chapterFactory().apply {
            this.url = boDomain(duong)
            name = tenChuong
            date_upload = doiNgay(o.selectFirst("div.chap_update")?.text())
            chapter_number = Regex("""\d+(?:\.\d+)?""").find(tenChuong)?.value?.toFloatOrNull() ?: -1f
        }
    }

    if (danhSach.isEmpty()) throw Exception(loiRong(functionName, url))

    // DOM đã là MỚI-NHẤT-TRƯỚC (đo `nguc-tu`: `20 -End`, `19`, `18`, …) — đúng thứ tự Tachiyomi
    // cần. KHÔNG sort lại theo số: chương đặt tên riêng (không có số) sẽ bị đẩy sai vị trí thời
    // gian, và CBZ sai thứ tự trang là hỏng mà không ai báo lỗi.
    return danhSach
}

// ── Trang ảnh ───────────────────────────────────────────────────────────────────────────────

fun parsePageList(html: String, functionName: String, url: String): List<Page> {
    val document = Jsoup.parse(html, url)

    // Ảnh nằm ở `data-src`; `src` là `/images/loadx-min.gif` (ảnh chờ lazyload). Đọc `src` cho ra
    // ĐỦ số trang mà toàn GIF, và không gì báo lỗi. Đo `trang-chuong.html`: 36 thẻ <img>, 33 thẻ
    // có `data-src`, cả 33 đều mang `uploads/minh_hoa/`; 3 thẻ còn lại là logo + ảnh loading.
    val anh = document.select(CUM_ANH)
        .map { it.absUrl("data-src") }
        .filter { laAnhTruyen(it) }

    if (anh.isEmpty()) throw Exception(loiRong(functionName, url))

    return anh.mapIndexed { i, duong -> Page(i, imageUrl = duong) }
}

// ── Dùng chung ──────────────────────────────────────────────────────────────────────────────

internal fun laAnhTruyen(url: String): Boolean = url.substringBefore('?').contains(DUONG_ANH)

private fun Element.linksTheoMau(mau: Regex): List<Element> = select("a[href]").filter { duongDan(it.absUrl("href"))?.matches(mau) == true }

private fun Element.linkTheoMau(mau: Regex): Element? = linksTheoMau(mau).firstOrNull()

/** Phần đường dẫn của một URL tuyệt đối, không host, không query. `null` nếu không phân tích được. */
private fun duongDan(url: String): String? {
    val sauGiaoThuc = url.substringAfter("://", missingDelimiterValue = "")
    if (sauGiaoThuc.isEmpty()) return null
    val duong = sauGiaoThuc.substringAfter('/', missingDelimiterValue = "")
    return "/" + duong.substringBefore('?').substringBefore('#')
}

/** Giống `HttpSource.setUrlWithoutDomain`: giữ đường dẫn (+ query nếu có), bỏ giao thức và host. */
internal fun boDomain(url: String): String {
    val sauGiaoThuc = url.substringAfter("://", missingDelimiterValue = "")
    if (sauGiaoThuc.isEmpty()) return url
    val duong = sauGiaoThuc.substringAfter('/', missingDelimiterValue = "")
    return "/" + duong.substringBefore('#')
}

/** Slug bộ, lấy được từ CẢ url trang bộ lẫn url trang chương. */
internal fun slugBo(url: String): String? {
    val duong = duongDan(url) ?: return null
    MAU_DUONG_BO.find(duong)?.value?.let {
        return it.removePrefix("/truyen-tranh/").removeSuffix(".html")
    }
    val doan = duong.trim('/').split('/')
    if (doan.size >= 2 && doan[0] == "truyen-tranh") return doan[1].removeSuffix(".html")
    return null
}

private fun Element.docThongTin(nhan: String): String? = select(".info-item")
    .firstOrNull { it.text().substringBefore(':').trim().equals(nhan, ignoreCase = true) }
    ?.text()?.substringAfter(':')?.trim()?.takeIf { it.isNotBlank() }

private fun String?.doiTrangThai(): Int = when {
    this == null -> SManga.UNKNOWN
    contains("Full", true) || contains("Hoàn", true) -> SManga.COMPLETED
    contains("Đang", true) || contains("Tiến hành", true) -> SManga.ONGOING
    else -> SManga.UNKNOWN
}

private fun doiNgay(chuoi: String?): Long = chuoi?.trim()?.let { runCatching { DINH_DANG_NGAY.parse(it)?.time }.getOrNull() } ?: 0L

private fun loiRong(functionName: String, url: String): String = "$functionName: không tìm thấy mục nào ở $url — site nhiều khả năng đã đổi giao diện."
