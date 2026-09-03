package eu.kanade.tachiyomi.extension.vi.nettruyentv

import eu.kanade.tachiyomi.multisrc.wpcomics.WPComics
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * nettruyentv.net — WordPress dùng theme riêng tên `truyentranh`.
 *
 * 🔴 NGUỒN NÀY CẦN VPN / PROXY.
 * Site chặn ở tầng SNI với đường ra trong nước: kết nối thẳng bị treo rồi trả về rỗng,
 * còn qua proxy thì trang chủ trả 200 (~248 KB). Người dùng không bật VPN sẽ thấy nguồn
 * TRỐNG — đó là **mạng**, không phải extension hỏng. Đừng đi sửa parser vì triệu chứng này.
 *
 * Đo ngày 2026-09-03, những chỗ site KHÁC wpcomics gốc (sai thì hỏng im lặng):
 *
 * 1. **Phân trang là `/page/N/`**, không phải `?page=N`. WPComics gốc dựng `?page=N`;
 *    để nguyên thì mọi trang đều ra trang 1.
 * 2. **Không có trang "hot" riêng.** `/truyen-tranh-hot`, `/truyen-hot` đều 404; `/hot`
 *    trả 200 nhưng chỉ là trang đoán-slug của WordPress, không có lưới truyện.
 *    ⇒ Phổ biến = Mới cập nhật = danh sách gốc `/page/N/` (đo được 515 trang, 32 bộ/trang).
 * 3. **Nút sang trang là `a.next.page-numbers`.** Selector mặc định của WPComics
 *    (`a.next-page, a[rel=next]`) KHÔNG khớp ⇒ `hasNextPage` luôn false, danh sách
 *    đứng im ở trang 1 mà không có lỗi nào.
 * 4. 🔴 **Tìm kiếm máy chủ HỎNG.** Form tìm kiếm của site còn gõ nhầm `acton=` thay vì
 *    `action=`. Mọi từ khoá `?s=` đều trả về CÙNG một danh sách 32 bộ (đo: `s=conan`,
 *    `s=naruto`, `s=onepiece`, `s=one+piece` cho kết quả giống hệt nhau, trùng 30/32 với
 *    trang chủ). Gọi `?s=` rồi tin kết quả là kiểu sai nguy hiểm nhất — trả về truyện
 *    không liên quan mà trông như tìm được. ⇒ Lọc TẠI MÁY, xem [getSearchMangaList].
 * 5. **Ảnh chương nằm trong IFRAME sang host khác** (`abc.newfeedcdn.site`), không nằm
 *    trong HTML trang chương. Xem [getPageList].
 *
 * ⚠️ **Nguồn ảnh THƯA — phần lớn chương không tải được, và đây KHÔNG phải lỗi parser.**
 * Host phát ảnh trả `200` kèm **thân rỗng 0 byte** cho rất nhiều chương. Đo One Piece
 * (`id=906`) trên 15 chương rải đều: chỉ 3 chương (2, 10, 1191) có nội dung, 12 chương
 * còn lại 0 byte — gồm cả chương 1, 1190 và các chương mới sát đỉnh. Đã loại trừ nguyên
 * nhân header: chương 500 ra 0 byte ở **cả bốn** biến thể (không header · chỉ UA ·
 * chỉ Referer · cả hai), trong khi chương 1191 luôn trả đủ 7.189 byte.
 *
 * ⇒ Người dùng sẽ gặp nhiều chương mở ra **rỗng**. Trước khi đi sửa [getPageList], hãy
 * `curl` thẳng URL iframe của đúng chương đó: ra 0 byte nghĩa là **site không có ảnh**,
 * không phải extension đọc sai.
 *
 * Những chỗ site GIỐNG wpcomics, dùng lại được nguyên:
 * - `article#item-detail` cùng `li.author/li.status/li.kind/div.col-image` ⇒ chi tiết truyện.
 * - `div.items div.item` ⇒ ô truyện trong lưới.
 * - `div.list-chapter li.row` ⇒ danh sách chương, **nằm sẵn trong HTML, không cần AJAX**
 *   (đo One Piece: 1265 chương trong một lần tải trang).
 * - Ngày chương ghi tương đối ("13 ngày trước", "13 tháng trước") ⇒ `toDate()` của
 *   WPComics xử lý được sẵn.
 */
@Source
abstract class NetTruyenTV : WPComics() {

    // Ngày chương gần như luôn là dạng tương đối; định dạng này chỉ là đường lui cho
    // mốc tuyệt đối kiểu "22-08-2026 23:25:42" ở khối "Cập nhật lúc".
    override val dateFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss", Locale.US)

    override val gmtOffset: String? = null

    // Trang liệt kê thể loại, cũng là trang lấy danh sách thể loại cho bộ lọc.
    override val searchPath = "the-loai-truyen"

    // ============================== Danh sách ==============================

    /** WordPress phân trang bằng `/page/N/`; trang 1 là chính đường dẫn gốc. */
    private fun listUrl(base: String, page: Int): String = if (page > 1) "$base/page/$page/" else "$base/"

    override fun popularMangaNextPageSelector() = "a.next.page-numbers"

    override suspend fun getPopularManga(page: Int): MangasPage = parseMangaPage(
        client.get(listUrl(baseUrl, page)),
        popularMangaSelector(),
        ::popularMangaFromElement,
    )

    // Site không có bảng xếp hạng riêng để lấy, nên "Phổ biến" và "Mới cập nhật" là một.
    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    // Ô truyện ở lưới tìm kiếm và lưới trang chủ là cùng một markup. Dùng lại để tránh
    // selector `div.image a img` của lớp cha — nó dùng `!!` nên ô thiếu ảnh sẽ ném lỗi.
    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    // ============================== Tìm kiếm ==============================

    /**
     * Site không có tìm kiếm máy chủ dùng được (xem ghi chú (4) ở đầu lớp), nên:
     * - Có chọn **thể loại** ⇒ đi theo đường `/the-loai-truyen/<slug>/page/N/`, đây là
     *   lọc THẬT do máy chủ làm.
     * - Có gõ **từ khoá** ⇒ lấy trang danh sách rồi **lọc theo tiêu đề tại máy**.
     *
     * ⚠️ Lọc tại máy chỉ soi được các bộ nằm trên trang đang tải, nên kết quả ĐÚNG nhưng
     * KHÔNG ĐẦY ĐỦ — người dùng phải cuộn thêm trang. Đây là đánh đổi có chủ ý: thà trả
     * ít mà đúng, còn hơn gọi `?s=` để nhận về một danh sách không liên quan.
     */
    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.toUriPart()
        val base = if (genre != null) "$baseUrl/$searchPath/$genre" else baseUrl

        val result = parseMangaPage(
            client.get(listUrl(base, page)),
            searchMangaSelector(),
            ::searchMangaFromElement,
        )

        if (query.isBlank()) return result

        val needle = query.trim().lowercase(Locale.ROOT)
        return MangasPage(
            result.mangas.filter { it.title.lowercase(Locale.ROOT).contains(needle) },
            result.hasNextPage,
        )
    }

    // ============================== Bộ lọc ==============================

    // Danh sách thể loại nằm ở menu đầu trang (đo được 51 thể loại), không nằm trong
    // `.genres ul.nav` như wpcomics gốc.
    override val genresSelector = "ul.sub-menu li a[href*=/the-loai-truyen/]"

    // Site không có tham số lọc tình trạng nào chạy được, nên chỉ bày bộ lọc thể loại —
    // đừng bày ra thứ bấm vào không đổi gì.
    override fun getFilterList(genres: List<Pair<String?, String>>): FilterList = FilterList(
        buildList {
            if (genres.isNotEmpty()) {
                add(GenreFilter(intl["GENRE"], genres))
            }
        },
    )

    // ============================== Chi tiết ==============================

    override fun mangaDetailsParse(document: Document): SManga = super.mangaDetailsParse(document).apply {
        // Phần mô tả nằm trong `div.shortened` và gồm nhiều thẻ `div`, không phải `p`
        // như selector mặc định của lớp cha.
        document.select("article#item-detail div.detail-content div.shortened")
            .joinToString("\n\n") { it.wholeText().trim() }
            .takeIf { it.isNotBlank() }
            ?.let { description = it }
    }

    // ============================== Trang ảnh ==============================

    /**
     * Trang chương chỉ là cái vỏ: khối `div.reading-detail` chứa một `<iframe>` trỏ sang
     * host phát ảnh riêng, ảnh thật nằm trong tài liệu của iframe đó dưới dạng
     * `div.page-chapter > img[data-src]` (đo One Piece chương 1191: 21 ảnh).
     *
     * Đo được host ảnh KHÔNG khoá `Referer` hay `User-Agent`: cả bốn biến thể (không
     * header · chỉ UA · chỉ Referer · cả hai) đều trả 200 và đúng 288.846 byte. Nên
     * không cần dựng header riêng ở đây. Ảnh gốc phía sau lại chặn (403) nếu gọi thẳng,
     * vì vậy phải giữ nguyên URL bọc mà iframe đưa ra, đừng bóc tham số `linkimage` ra dùng.
     */
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()

        val frameUrl = document.selectFirst("div.reading-detail iframe[src]")
            ?.absUrl("src")
            ?.takeIf { it.isNotBlank() }

        // Không có iframe thì đọc thẳng trang chương — đường lui phòng khi site đổi cách bày ảnh.
        val pageDocument = if (frameUrl == null) document else client.get(frameUrl).asJsoup()

        return pageDocument.select(pageListSelector)
            .mapNotNull { imageOrNull(it) }
            .distinct()
            .mapIndexed { index, imageUrl -> Page(index, "", imageUrl) }
    }
}
