package eu.kanade.tachiyomi.extension.vi.fapmanga

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.json.JsonObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URISyntaxException

// Đường dẫn trang bộ truyện là 1 tầng: https://fapmanga.com/truyen-hentai/<slug>(/)
// Không được khớp trang chương (2 tầng) hay trang thể loại (/the-loai/...).
private val MANGA_URL_REGEX = Regex("""^https://fapmanga\.com/truyen-hentai/[^/]+/?$""")

/**
 * Hàm thuần (không mạng, không suspend) để bóc tách danh sách truyện từ một trang HTML
 * dạng lưới thẻ (danh sách, bảng xếp hạng, kết quả tìm kiếm...) của FapManga.
 *
 * [functionName] và [url] chỉ dùng để làm rõ thông báo lỗi khi kết quả rỗng.
 */
fun parseMangaList(html: String, functionName: String, url: String): MangasPage {
    val document = Jsoup.parse(html, url)

    // Toàn bộ liên kết trang bộ truyện xuất hiện bất kỳ đâu trong trang (kể cả sidebar/footer).
    val allCandidateLinks = document.select("a[href]")
        .filter { MANGA_URL_REGEX.matches(it.absUrl("href")) }

    // Cắt phạm vi: chọn tổ tiên chung chứa NHIỀU liên kết khớp mẫu nhất, để loại các liên kết
    // lặp lại ở widget ngoài khối danh sách chính (đo được có trang lặp ~10 slug từ sidebar).
    val scope = findAncestorWithMostMatches(allCandidateLinks)

    val scopedLinks = if (scope != null) {
        scope.select("a[href]").filter { MANGA_URL_REGEX.matches(it.absUrl("href")) }
    } else {
        allCandidateLinks
    }

    val mangas = scopedLinks
        .distinctBy { it.absUrl("href") }
        .map { link -> mangaFromLink(link) }

    if (mangas.isEmpty()) {
        throw Exception("$functionName: empty result from $url")
    }

    val hasNextPage = document.selectFirst("link[rel=next], a[rel=next]") != null

    return MangasPage(mangas, hasNextPage)
}

private fun findAncestorWithMostMatches(links: List<Element>): Element? {
    val counts = LinkedHashMap<Element, Int>()
    for (link in links) {
        for (ancestor in link.parents()) {
            counts[ancestor] = (counts[ancestor] ?: 0) + 1
        }
    }
    return counts.maxByOrNull { it.value }?.key
}

private fun mangaFromLink(link: Element): SManga = SimpleManga().apply {
    url = toUrlWithoutDomain(link.absUrl("href"))
    title = link.selectFirst(".title")?.text()
        ?: link.attr("title").ifBlank { link.text() }
    thumbnail_url = link.selectFirst("img")?.absUrl("src")?.ifBlank { null }
}

// SManga.create() (companion factory của extensions-lib) là hàm "Stub!" khi biên dịch/chạy
// ngoài app Mihon thật (nó được app cấp implementation lúc runtime, không có sẵn trong unit
// test JVM thuần). Dùng cài đặt tối giản của chính mình cho hàm thuần này để test được độc lập.
@Suppress("PropertyName")
private class SimpleManga : SManga {
    override var url: String = ""
    override var title: String = ""
    override var thumbnail_url: String? = null
    override var artist: String? = null
    override var author: String? = null
    override var status: Int = SManga.UNKNOWN
    override var description: String? = null
    override var genre: String? = null
    override var update_strategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE
    override var memo: JsonObject = JsonObject(emptyMap())
    override var initialized: Boolean = false
}

// Hàm thuần thay cho HttpSource.setUrlWithoutDomain (chỉ gọi được khi "this" là HttpSource,
// không dùng được ở hàm top-level). Theo đúng quy ước: SManga.url lưu đường dẫn tương đối
// (path + query + fragment), không lưu domain, để đổi mirror domain không phải sửa dữ liệu đã lưu.
private fun toUrlWithoutDomain(absoluteUrl: String): String = try {
    val uri = URI(absoluteUrl)
    buildString {
        append(uri.rawPath.orEmpty())
        uri.rawQuery?.let { append('?').append(it) }
        uri.rawFragment?.let { append('#').append(it) }
    }
} catch (e: URISyntaxException) {
    absoluteUrl
}
