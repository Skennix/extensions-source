package eu.kanade.tachiyomi.extension.vi.fapmanga

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URISyntaxException

/**
 * Hàm thuần (không mạng, không suspend) để bóc tách danh sách truyện từ một trang HTML
 * dạng lưới thẻ (danh sách, bảng xếp hạng, kết quả tìm kiếm...) của FapManga.
 *
 * [baseUrl] dùng để dựng regex nhận diện url trang bộ truyện (1 tầng, dạng
 * "$baseUrl/truyen-hentai/<slug>(/)"), tránh khớp trang chương (2 tầng) hay trang thể loại.
 * [functionName] và [url] chỉ dùng để làm rõ thông báo lỗi khi kết quả rỗng.
 * [mangaFactory] cho phép test JVM tiêm cài đặt SManga giả, thay vì SManga.create() thật
 * (hàm "Stub!" khi biên dịch/chạy ngoài app Mihon thật, chỉ được app cấp implementation lúc runtime).
 */
fun parseMangaList(
    html: String,
    baseUrl: String,
    functionName: String,
    url: String,
    mangaFactory: () -> SManga = { SManga.create() },
): MangasPage {
    val document = Jsoup.parse(html, url)
    val mangaUrlRegex = Regex("""^${Regex.escape(baseUrl)}/truyen-hentai/[^/?#]+/?$""")

    // Toàn bộ liên kết trang bộ truyện xuất hiện bất kỳ đâu trong trang (kể cả sidebar/footer).
    val allCandidateLinks = document.select("a[href]")
        .filter { mangaUrlRegex.matches(it.absUrl("href")) }

    // Cắt phạm vi: nhóm các link theo CONTAINER của cụm anh-em đồng dạng chứa chúng (cha của
    // "item lặp" — phần tử cha trực tiếp của link), rồi chọn container có nhiều item con nhất.
    // Không dùng "tổ tiên chứa nhiều link nhất" vì phép đó luôn hội tụ về <body>/<main> (một tổ
    // tiên càng cao càng chứa nhiều link con cháu, kể cả khi chúng nằm ở nhiều widget khác nhau);
    // đo được trên rank-week-p1.html: <main> gộp cả 6 widget "Truyện HOT" + 30 lưới chính +
    // 10 sidebar ".rank-list" thành một khối 46 link, không cắt được gì.
    val scope = findLargestSiblingGroupContainer(allCandidateLinks)

    val scopedLinks = if (scope != null) {
        scope.select("a[href]").filter { mangaUrlRegex.matches(it.absUrl("href")) }
    } else {
        allCandidateLinks
    }

    val mangas = scopedLinks
        // Chuẩn hoá bỏ trailing slash trước khi loại trùng, vì "/slug" và "/slug/" là cùng một trang.
        .distinctBy { it.absUrl("href").trimEnd('/') }
        .map { link -> mangaFromLink(link, mangaFactory) }
        .filter { it.title.isNotBlank() }

    if (mangas.isEmpty()) {
        throw Exception("$functionName: empty result from $url")
    }

    val hasNextPage = document.selectFirst("link[rel=next], a[rel=next]") != null

    return MangasPage(mangas, hasNextPage)
}

/**
 * Hàm thuần bóc tách chi tiết một bộ truyện (title, thumbnail, mô tả, thể loại nếu có) từ trang
 * bộ truyện của FapManga.
 *
 * Trang bộ truyện (fixture bo-truyen.html, đo 2026-08-17) KHÔNG gắn class/id riêng cho khối
 * thông tin (chỉ có <h1>/<p> trần), nên các selector dưới đây neo theo CẤU TRÚC (thẻ + vị trí
 * cha-con), không theo tên class suy đoán.
 */
fun parseMangaDetails(
    html: String,
    baseUrl: String,
    url: String,
    mangaFactory: () -> SManga = { SManga.create() },
): SManga {
    val document = Jsoup.parse(html, url)
    val article = document.selectFirst("article")
    // Khối thông tin (ảnh bìa + tên + mô tả) là div anh em với ảnh bìa, không có class riêng:
    // xác định bằng "div cha của <h1>" (h1 là mốc chắc chắn duy nhất của khối này).
    val infoBlock = document.selectFirst("h1")?.parent()

    return mangaFactory().apply {
        title = document.selectFirst("h1")?.text()?.ifBlank { null } ?: ""
        thumbnail_url = article?.selectFirst("img")?.absUrl("src")?.ifBlank { null }
        // Đoạn <p> đầu trong khối thông tin là lượt xem (VD "1.252 Lượt xem"), không phải mô tả;
        // mô tả thật (nếu có) là <p> còn lại không chứa "Lượt xem".
        description = infoBlock?.select("p")
            ?.firstOrNull { !it.text().contains("Lượt xem") }
            ?.text()?.ifBlank { null }
        // Thể loại: chỉ lấy link "/the-loai/" nằm TRONG <article> (nội dung bài viết), không lấy
        // link header/footer (những link "/the-loai/" ở header nav và footer "Khám phá thêm" xuất
        // hiện GIỐNG HỆT nhau trên mọi trang của site, không phải thể loại riêng của bộ truyện
        // này — lấy nhầm sẽ khiến MỌI bộ truyện đều báo cùng một danh sách thể loại giả).
        // Fixture bo-truyen.html hiện KHÔNG có thể loại riêng nào cho bộ này trong <article>,
        // nên genre hợp lệ là null ở đây — không bịa ra giá trị.
        genre = article?.select("a[href*=\"/the-loai/\"]")
            ?.map { it.text().trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(", ")
    }
}

/**
 * Hàm thuần bóc tách danh sách chương từ trang bộ truyện của FapManga.
 *
 * Trang bộ truyện (fixture bo-truyen.html) KHÔNG có class/id cho khối chương (chỉ có tiêu đề
 * "<h2>Chương</h2>" trần, không gắn với khối theo cấu trúc DOM nào tin cậy được) — điểm gãy im
 * lặng thật của site. Selector neo theo MẪU URL 2 tầng ("$baseUrl/truyen-hentai/<bo>/<chuong>")
 * + cụm anh-em đồng dạng (tái dùng [findLargestSiblingGroupContainer] từ parseMangaList), CẤM
 * nth-child vì thứ tự DOM của chương không đáng tin (xem lý do sắp xếp lại bên dưới).
 */
fun parseChapterList(
    html: String,
    baseUrl: String,
    functionName: String,
    url: String,
    chapterFactory: () -> SChapter = { SChapter.create() },
): List<SChapter> {
    val document = Jsoup.parse(html, url)
    val chapterUrlRegex = Regex("""^${Regex.escape(baseUrl)}/truyen-hentai/[^/?#]+/[^/?#]+/?$""")

    val allCandidateLinks = document.select("a[href]")
        .filter { chapterUrlRegex.matches(it.absUrl("href")) }

    val scope = findLargestSiblingGroupContainer(allCandidateLinks)
    val scopedLinks = if (scope != null) {
        scope.select("a[href]").filter { chapterUrlRegex.matches(it.absUrl("href")) }
    } else {
        allCandidateLinks
    }

    val chapters = scopedLinks
        .distinctBy { it.absUrl("href").trimEnd('/') }
        .map { link -> chapterFromLink(link, chapterFactory) }
        // Thứ tự chương trong DOM của fixture thật KHÔNG đơn điệu tăng dần (VD: chap-3 đứng
        // trước chap-2), nên không thể coi "DOM đảo ngược" là "mới nhất trước". Sắp lại theo số
        // chương trích từ url (giảm dần) để chương số lớn nhất luôn đứng đầu, đúng chuẩn Suwayomi.
        .sortedByDescending { it.chapter_number }

    if (chapters.isEmpty()) {
        throw Exception("$functionName: empty result from $url")
    }

    return chapters
}

private fun chapterFromLink(link: Element, chapterFactory: () -> SChapter): SChapter = chapterFactory().apply {
    val absHref = link.absUrl("href")
    url = toUrlWithoutDomain(absHref)
    name = link.selectFirst(".chapter-list-title")?.text()?.ifBlank { null }
        ?: link.attr("title").ifBlank { link.text() }
    // Số chương dùng để sắp xếp "mới nhất trước": lấy từ slug dạng "chap-<số>" ở cuối url. Chương
    // đặt tên riêng (không theo mẫu số, VD "tessia-eralith-...") nhận -1f để luôn xếp sau mọi
    // chương có số — không có markup ngày đăng nào trên trang này để suy ra vị trí đúng hơn.
    chapter_number = Regex("""chap-(\d+(?:\.\d+)?)/?$""").find(absHref)
        ?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
    // Không có markup ngày đăng cho khối chương trên trang này (chỉ có "N Lượt xem") — không bịa
    // date_upload, để mặc định 0L của SChapter.create().
}

// Với mỗi link, "item lặp" là phần tử cha trực tiếp của nó (VD: <article class="manga-card">
// hay <div class="rank-item">), và "container" là cha của item đó (VD: <div class="card-grid">
// hay <div class="rank-list">). Đếm số item khác nhau (không phải số link, để tránh lệch nếu một
// item có nhiều link) trên mỗi container, chọn container nhiều item nhất — đó là cụm anh-em đồng
// dạng lớn nhất, tức khối danh sách chính, không phải widget hay sidebar.
private fun findLargestSiblingGroupContainer(links: List<Element>): Element? {
    val itemsByContainer = LinkedHashMap<Element, MutableSet<Element>>()
    for (link in links) {
        val item = link.parent() ?: continue
        val container = item.parent() ?: continue
        itemsByContainer.getOrPut(container) { mutableSetOf() }.add(item)
    }
    return itemsByContainer.maxByOrNull { it.value.size }?.key
}

private fun mangaFromLink(link: Element, mangaFactory: () -> SManga): SManga = mangaFactory().apply {
    url = toUrlWithoutDomain(link.absUrl("href"))
    title = link.selectFirst(".title")?.text()?.ifBlank { null }
        ?: link.attr("title").ifBlank { link.text() }
    thumbnail_url = link.selectFirst("img")?.absUrl("src")?.ifBlank { null }
}

// Hàm thuần thay cho HttpSource.setUrlWithoutDomain (chỉ gọi được khi "this" là HttpSource,
// không dùng được ở hàm top-level). Theo đúng quy ước: SManga.url lưu đường dẫn tương đối
// (path + query + fragment), không lưu domain, để đổi mirror domain không phải sửa dữ liệu đã lưu.
// replace(" ", "%20") khớp bản gốc HttpSource.toUriCompat trước khi URI parse: một số url chứa
// khoảng trắng (chưa encode) làm URI ném URISyntaxException nếu không thay trước.
private fun toUrlWithoutDomain(absoluteUrl: String): String = try {
    val uri = URI(absoluteUrl.replace(" ", "%20"))
    buildString {
        append(uri.rawPath.orEmpty())
        uri.rawQuery?.let { append('?').append(it) }
        uri.rawFragment?.let { append('#').append(it) }
    }
} catch (e: URISyntaxException) {
    // Không trả nguyên chuỗi tuyệt đối (sẽ lẫn domain vào dữ liệu lưu trữ). Cắt domain bằng
    // cách tìm dấu "/" đầu tiên sau phần "://" làm phương án dự phòng.
    val schemeEnd = absoluteUrl.indexOf("://")
    val pathStart = if (schemeEnd >= 0) absoluteUrl.indexOf("/", schemeEnd + 3) else -1
    if (pathStart >= 0) absoluteUrl.substring(pathStart) else absoluteUrl
}
