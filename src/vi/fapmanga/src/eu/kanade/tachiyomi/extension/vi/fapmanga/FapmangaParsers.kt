package eu.kanade.tachiyomi.extension.vi.fapmanga

import eu.kanade.tachiyomi.source.model.MangasPage
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
