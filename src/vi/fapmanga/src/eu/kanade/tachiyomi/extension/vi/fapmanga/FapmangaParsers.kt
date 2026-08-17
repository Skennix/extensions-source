package eu.kanade.tachiyomi.extension.vi.fapmanga

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder

// Host CDN ảnh trang chương của FapManga, đo thật 2026-08-17 trên fixture chuong.html (61 ảnh
// "cdn.fapmanga.com/manga/.../readers-paradise-chap-2-<n>.webp"). Neo bằng SO KHỚP HOST TUYỆT ĐỐI
// (host == CDN_HOST), KHÔNG dùng "host kết thúc bằng host của baseUrl": trang chương chứa hẳn 2
// host khác (ad banner "cdn.hentai.pictures", "data:" URI) không kết thúc bằng "fapmanga.com" nên
// cả 2 cách đều loại được — nhưng site tự quảng cáo "mirror domains" (CSS .mirror-bar/.footer-mirrors
// trong chính fixture) nghĩa là domain TRANG (baseUrl) có thể đổi theo mirror trong khi CDN ảnh
// (hạ tầng lưu trữ, tách biệt khỏi domain hiển thị) nhiều khả năng KHÔNG đổi theo — so khớp cứng
// theo baseUrl sẽ gãy ngay khi Suwayomi cấu hình mirror khác domain gốc. Đổi CDN thật thì sửa Ở ĐÂY.
// internal (không private): Fapmanga.kt (configureClient) cần đúng host này để đặt nhịp rate-limit
// riêng cho CDN, tránh 2 nơi định nghĩa host CDN lệch nhau.
internal const val CDN_HOST = "cdn.fapmanga.com"

/**
 * Hàm thuần (không mạng, không suspend) để bóc tách danh sách truyện từ một trang HTML
 * dạng lưới thẻ (danh sách, bảng xếp hạng, kết quả tìm kiếm...) của FapManga.
 *
 * [baseUrl] dùng để dựng regex nhận diện url trang bộ truyện (1 tầng, dạng
 * "$baseUrl/truyen-hentai/<slug>(/)"), tránh khớp trang chương (2 tầng) hay trang thể loại.
 * [functionName] và [url] chỉ dùng để làm rõ thông báo lỗi khi kết quả rỗng.
 * [allowEmpty] CHỈ dùng cho đường tìm kiếm có từ khoá (getSearchMangaList): kết quả rỗng khi user
 * gõ từ khoá lạ là HỢP LỆ (ngoại lệ có chủ đích, chốt T4-plan mục 8.3), không phải lỗi parser —
 * true thì trả `MangasPage(emptyList(), false)` thay vì ném lỗi. Mọi lời gọi khác (danh sách, xếp
 * hạng, thể loại) giữ nguyên luật cứng "rỗng sau lọc thì ném lỗi", nên để mặc định false.
 * [mangaFactory] cho phép test JVM tiêm cài đặt SManga giả, thay vì SManga.create() thật
 * (hàm "Stub!" khi biên dịch/chạy ngoài app Mihon thật, chỉ được app cấp implementation lúc runtime).
 * Đặt SAU [allowEmpty] (không phải ngay sau [url]) để giữ vị trí THAM SỐ CUỐI CÙNG — mọi lời gọi
 * test hiện có dùng cú pháp trailing lambda cho tham số này, đổi vị trí sẽ làm gãy toàn bộ.
 */
fun parseMangaList(
    html: String,
    baseUrl: String,
    functionName: String,
    url: String,
    allowEmpty: Boolean = false,
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
        if (allowEmpty) {
            return MangasPage(emptyList(), false)
        }
        throw Exception("$functionName: empty result from $url")
    }

    val hasNextPage = document.selectFirst("link[rel=next], a[rel=next]") != null

    return MangasPage(mangas, hasNextPage)
}

/**
 * Kết quả quyết định của [buildSearchRequest]: URL cần gọi (null nghĩa là "không có tiêu chí lọc
 * nào, rơi về getLatestUpdates"), [allowEmpty] truyền thẳng cho tham số cùng tên của
 * [parseMangaList], và [forceNoNextPage] báo hiệu phải ép `hasNextPage = false` sau khi parse
 * (đường "?s=" — đo #3, site không phân trang kết quả tìm kiếm).
 */
internal data class SearchRequest(
    val url: String?,
    val allowEmpty: Boolean,
    val forceNoNextPage: Boolean,
)

/**
 * Hàm thuần quyết định URL và cách xử lý kết quả cho getSearchMangaList (GĐ5) — tách riêng khỏi
 * hàm suspend thật để test JVM kiểm được LUẬT ƯU TIÊN mà không cần gọi mạng:
 *
 * 1. [query] có chữ (sau trim) ⇒ luôn dùng "$baseUrl/?s=<urlencode(query)>", BỎ QUA [genreSlug]
 *    dù có chọn (đo #3: site không có markup nào cho biết hỗ trợ kết hợp s+the-loai — chốt T8-plan,
 *    xem RESEARCH.md nếu sau này đo lại ra kết quả khác).
 * 2. [query] rỗng, [genreSlug] có chữ ⇒ "$baseUrl/the-loai/<genreSlug>?page=<page>".
 * 3. Cả hai đều rỗng ⇒ trả [SearchRequest.url] = null, tầng gọi tự rơi về getLatestUpdates(page).
 */
internal fun buildSearchRequest(baseUrl: String, page: Int, query: String, genreSlug: String): SearchRequest = when {
    query.isNotBlank() -> SearchRequest(
        url = "$baseUrl/?s=${URLEncoder.encode(query, "UTF-8")}",
        allowEmpty = true,
        forceNoNextPage = true,
    )
    genreSlug.isNotBlank() -> SearchRequest(
        url = "$baseUrl/the-loai/$genreSlug?page=$page",
        allowEmpty = false,
        forceNoNextPage = false,
    )
    else -> SearchRequest(url = null, allowEmpty = false, forceNoNextPage = false)
}

/**
 * Hàm thuần bóc tách chi tiết một bộ truyện (title, thumbnail, mô tả, thể loại nếu có) từ trang
 * bộ truyện của FapManga.
 *
 * Trang bộ truyện (fixture bo-truyen.html, đo 2026-08-17) KHÔNG gắn class/id riêng cho khối
 * thông tin (chỉ có <h1>/<p> trần), nên các selector dưới đây neo theo CẤU TRÚC (thẻ + vị trí
 * cha-con), không theo tên class suy đoán.
 *
 * Không nhận tham số baseUrl: mọi giá trị tuyệt đối hoá (thumbnail...) dùng document base uri
 * dựng từ [url] (Jsoup.parse(html, url)), không cần domain truyền riêng — khác parseChapterList,
 * hàm đó thật sự cần baseUrl để dựng regex neo domain cho link chương.
 */
fun parseMangaDetails(
    html: String,
    functionName: String,
    url: String,
    mangaFactory: () -> SManga = { SManga.create() },
): SManga {
    val document = Jsoup.parse(html, url)
    val article = document.selectFirst("article")

    val title = document.selectFirst("h1")?.text()?.ifBlank { null } ?: ""
    if (title.isBlank()) {
        throw Exception("$functionName: empty title from $url")
    }

    return mangaFactory().apply {
        this.title = title
        // Đo 2026-08-17: 1/48 thẻ trong search-one.html không có ảnh bìa (site render
        // .manga-card-placeholder). Nếu bộ truyện đang bóc tách rơi vào ca đó, <article> vẫn còn
        // ảnh của 8 bộ "Truyện liên quan" phía dưới — selectFirst("img") trong <article> sẽ ăn
        // nhầm bìa của bộ khác. og:image (thẻ <meta> riêng cho ĐÚNG bộ này, do site tự render)
        // không có vấn đề đó: không có bìa thì og:image trỏ ảnh mặc định của site hoặc rỗng,
        // không bao giờ trỏ sang bộ liên quan.
        thumbnail_url = document.selectFirst("meta[property=og:image]")?.absUrl("content")?.ifBlank { null }
        // Mô tả: dùng <meta name="description"> (do site tự render riêng cho từng bộ, đo khớp
        // 100% nội dung <p> mô tả trong <article>) thay vì heuristic "loại <p> chứa nhãn Lượt
        // xem" — heuristic đó gãy ngay khi site đổi nhãn hoặc thứ tự <p>.
        description = document.selectFirst("meta[name=description]")?.attr("content")?.ifBlank { null }
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
 * Trang bộ truyện (fixture bo-truyen.html) CÓ khối chương "div.chapter-list" (đo 2026-08-17,
 * comment cũ ở đây ghi "không có class" là SAI — sửa lại) nên selector chính neo thẳng vào đó;
 * nếu site mất class này (điểm gãy im lặng), rơi về phương án cũ: cụm anh-em đồng dạng (tái dùng
 * [findLargestSiblingGroupContainer] từ parseMangaList).
 *
 * Regex link chương NEO CẢ slug bộ truyện (trích từ [url]), không chỉ mẫu 2 tầng chung chung:
 * link chương của MỘT BỘ KHÁC (VD lọt vào qua widget "Truyện liên quan" ở cuối trang, hoặc
 * widget gợi ý tương lai) sẽ không khớp regex, dù nó vẫn là url 2 tầng hợp lệ của site.
 *
 * CẤM nth-child vì thứ tự DOM của chương không đáng tin theo số chương (xem lý do sắp xếp lại
 * bên dưới) — nhưng thứ tự DOM (đảo ngược) vẫn là thứ tự thời gian đăng thật của site.
 */
fun parseChapterList(
    html: String,
    baseUrl: String,
    functionName: String,
    url: String,
    chapterFactory: () -> SChapter = { SChapter.create() },
): List<SChapter> {
    val document = Jsoup.parse(html, url)

    // Slug của CHÍNH bộ truyện đang bóc tách, trích từ url trang bộ (VD ".../readers-paradise"
    // -> "readers-paradise"). Không suy ra được slug thì không thể neo phạm vi an toàn.
    val slug = url.substringAfter("/truyen-hentai/", "")
        .substringBefore('/').substringBefore('?').substringBefore('#')
        .ifBlank { null }
        ?: throw Exception("$functionName: cannot extract manga slug from $url")
    val chapterUrlRegex = Regex(
        """^${Regex.escape(baseUrl)}/truyen-hentai/${Regex.escape(slug)}/[^/?#]+/?$""",
    )

    fun candidateLinksIn(scope: Element) = scope.select("a[href]").filter { chapterUrlRegex.matches(it.absUrl("href")) }

    val chapterListBlock = document.selectFirst("div.chapter-list")
    val scopedLinks = if (chapterListBlock != null) {
        candidateLinksIn(chapterListBlock)
    } else {
        val allCandidateLinks = candidateLinksIn(document)
        val scope = findLargestSiblingGroupContainer(allCandidateLinks)
        if (scope != null) candidateLinksIn(scope) else allCandidateLinks
    }

    val chapters = scopedLinks
        .distinctBy { it.absUrl("href").trimEnd('/') }
        .map { link -> chapterFromLink(link, chapterFactory) }
        // Thứ tự DOM trong fixture thật là cũ->mới (đo 2026-08-17: chap-2..chap-20 theo đúng vị
        // trí đăng, kể cả chương đặt tên riêng "tessia-..." nằm đúng giữa chap-7 và chap-8; 2
        // chương đầu "chap-3" đứng trước "chap-2" là site tự xếp lạ, CHẤP NHẬN làm chân lý, không
        // sort lại theo số). Đảo ngược DOM để chương mới nhất lên đầu, đúng chuẩn Suwayomi.
        .reversed()

    if (chapters.isEmpty()) {
        throw Exception("$functionName: empty result from $url")
    }

    return chapters
}

/**
 * Hàm thuần bóc tách danh sách ảnh trang từ trang chương của FapManga.
 *
 * Trang chương (fixture chuong.html) chứa MỌI ảnh (kể cả ad banner ngoài host, ảnh đếm truy cập
 * dạng "data:" URI) lẫn trong cùng một trang phẳng, không có class riêng đáng tin cho khối ảnh đọc
 * (".reader-img" hiện có, nhưng site có thể đổi bất kỳ lúc nào — CHỌN neo theo host CDN thay vì
 * class, vì host là điều duy nhất KHÔNG thể lẫn giữa ảnh đọc thật và ad/tracking).
 *
 * GIỮ NGUYÊN thứ tự xuất hiện trong HTML (document.select trả theo thứ tự DOM), index từ 0 — khác
 * hẳn parseChapterList (đảo ngược DOM): trang chương không có khái niệm "mới nhất lên đầu", thứ tự
 * đọc phải đúng thứ tự vẽ trên trang.
 */
fun parsePageList(
    html: String,
    baseUrl: String,
    functionName: String,
    url: String,
    pageFactory: (Int, String) -> Page = { index, imageUrl -> Page(index, imageUrl = imageUrl) },
): List<Page> {
    val document = Jsoup.parse(html, url)

    val pages = document.select("img[src]")
        .filter { hostOfOrNull(it.absUrl("src")) == CDN_HOST }
        .mapIndexed { index, img -> pageFactory(index, img.absUrl("src")) }

    if (pages.isEmpty()) {
        throw Exception("$functionName: empty result from $url")
    }

    return pages
}

// URI(...).host thay vì kéo OkHttp (HttpUrl) vào chỉ để đọc host, giữ hàm thuần không phụ thuộc
// mạng. Trả null cho url không hợp lệ hoặc không có host (VD "data:image/gif;base64,...", một URI
// hợp lệ nhưng opaque, không có authority/host) để bị lọc ra êm như một host không khớp CDN_HOST,
// không làm sập cả hàm.
private fun hostOfOrNull(absoluteUrl: String): String? = try {
    URI(absoluteUrl).host
} catch (e: URISyntaxException) {
    null
}

private fun chapterFromLink(link: Element, chapterFactory: () -> SChapter): SChapter = chapterFactory().apply {
    val absHref = link.absUrl("href")
    url = toUrlWithoutDomain(absHref)
    name = link.selectFirst(".chapter-list-title")?.text()?.ifBlank { null }
        ?: link.attr("title").ifBlank { link.text() }
    // chapter_number KHÔNG dùng để sắp xếp (xem comment ở parseChapterList — thứ tự nền là DOM
    // đảo ngược), chỉ trích để điền field này cho Suwayomi (nó cần chapter_number để đối chiếu
    // "đã đọc tới đâu"). Lấy từ slug dạng "chap-<số>" ở cuối url; chương đặt tên riêng (không
    // theo mẫu số, VD "tessia-eralith-...") nhận -1f vì không có số nào để trích.
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
