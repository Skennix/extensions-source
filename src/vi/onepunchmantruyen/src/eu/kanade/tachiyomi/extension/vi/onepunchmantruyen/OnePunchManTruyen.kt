package eu.kanade.tachiyomi.extension.vi.onepunchmantruyen

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.utils.tryParse
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import java.text.Normalizer
import java.util.Locale
import kotlin.time.Instant

/**
 * Nguồn DẠNG MỘT BỘ: site chỉ đăng đúng một tác phẩm (One Punch Man).
 *
 * 🔴 Hợp đồng "không gọi mạng":
 *   - [fetchPopularManga], [fetchLatestUpdates], [fetchSearchManga], [fetchMangaDetails]
 *     trả về HẰNG SỐ trong mã, KHÔNG phát một request nào.
 *     Lý do: app Xưởng Truyện có chức năng "Tìm mọi nguồn" bắn getSearchManga tới MỌI
 *     nguồn đang cài (đo được 79–138 nguồn). Một nguồn một-bộ mà gọi mạng ở đây thì mọi
 *     lượt tìm đều tốn thêm một request rác tới một site chỉ có đúng một truyện.
 *   - Chỉ [chapterListParse] và [pageListParse] mới được đụng tới mạng.
 */
@Source
abstract class OnePunchManTruyen : HttpSource() {

    override val supportsLatest = true

    // ---------------------------------------------------------------- Hằng số của bộ truyện

    private val theManga: SManga
        get() = SManga.create().apply {
            url = MANGA_URL
            title = MANGA_TITLE
            author = "ONE"
            artist = "Yusuke Murata"
            status = SManga.ONGOING
            genre = "Hành động, Hài hước, Siêu nhiên, Seinen"
            thumbnail_url = MANGA_THUMBNAIL
            description = MANGA_DESCRIPTION
            initialized = true
        }

    // ---------------------------------------------------------------- Duyệt (KHÔNG gọi mạng)

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable.just(MangasPage(listOf(theManga), false))

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = Observable.just(MangasPage(listOf(theManga), false))

    /**
     * Trả lời hoàn toàn từ hằng số: khớp từ khoá (so KHÔNG DẤU, không phân biệt hoa thường)
     * thì trả về bộ truyện, không khớp thì trả danh sách RỖNG. Không một lời gọi HTTP nào.
     */
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        val hit = matchesQuery(query)
        return Observable.just(MangasPage(if (hit) listOf(theManga) else emptyList(), false))
    }

    private fun matchesQuery(query: String): Boolean {
        val q = query.normalizeForSearch()
        if (q.isEmpty()) return true
        return ALIASES.any { alias ->
            // từ khoá đủ dài thì cho khớp một phần trong tên bộ
            (q.length >= MIN_PARTIAL_LEN && alias.contains(q)) || q.contains(alias)
        }
    }

    // ---------------------------------------------------------------- Chi tiết (KHÔNG gọi mạng)

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable.just(theManga)

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + MANGA_URL, headers)

    override fun getMangaUrl(manga: SManga): String = baseUrl + MANGA_URL

    // ---------------------------------------------------------------- Danh sách chương (CÓ gọi mạng)

    /**
     * Nguồn danh sách chương: CHÍNH TRANG CHỦ.
     *
     * Đo 2026-09-01: trang chủ (~284 KB) liệt kê ĐỦ 358 chương trong MỘT request.
     * Trang `/all-chap/` cũng có danh sách nhưng phân trang 10 bài/trang × 36 trang
     * ⇒ 36 request cho cùng một kết quả. Trang chương chỉ có 6 link điều hướng, không
     * dùng được. Chọn trang chủ vì rẻ nhất mà vẫn đủ.
     *
     * ⚠️ KHÔNG suy theo dãy số: đo được 4 số trống thật (157, 163, 168, 261 đều trả 404),
     * số cao nhất là 362 nhưng chỉ tồn tại 358 chương.
     */
    override fun chapterListRequest(manga: SManga): Request = GET("$baseUrl/", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        return document.select("div.post-item a.plain[href]")
            .filter { it.attr("abs:href").contains(CHAPTER_SLUG) }
            .map { element ->
                val href = element.attr("abs:href")
                SChapter.create().apply {
                    url = href.toPath()
                    name = element.selectFirst("h5.post-title")?.text()?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?: href.trim('/').substringAfterLast('/')
                    chapter_number = CHAPTER_NUMBER_REGEX.find(href)
                        ?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
                    date_upload = Instant.tryParse(
                        element.selectFirst("time[datetime]")?.attr("datetime"),
                    )
                }
            }
            .filter { it.url.isNotEmpty() }
            .distinctBy { it.url }
    }

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    // ---------------------------------------------------------------- Trang ảnh (CÓ gọi mạng)

    /**
     * Ảnh nằm trong `div.entry-content` của trang chương.
     *
     * ⚠️ KHÔNG khoá cứng theo host CDN, và KHÔNG quét mù mọi `<img>` trong trang.
     * Đo 2026-09-01 trên chính site này:
     *   - chương 1/100/250/355 → ảnh trên `sv1.iiiiiiiiii.top`
     *   - chương 362 (mới nhất) → ảnh trên chính `onepunchmantruyen.com/wp-content/uploads`
     * Khoá theo host thì một trong hai nhóm sẽ ra 0 trang trong im lặng.
     * Cắt theo `div.entry-content` thì đúng cả hai, và loại sạch quảng cáo cờ bạc
     * (đo: 21/21, 15/15, 47/47, 16/16 ảnh — không lẫn ảnh nào khác).
     * Hai `<img>` logo của site nằm NGOÀI `entry-content` nên không lọt vào.
     */
    override fun pageListParse(response: Response): List<Page> {
        val content = response.asJsoup().selectFirst("div.entry-content")
            ?: return emptyList()

        return content.select("img[src]")
            .mapNotNull { it.attr("abs:src").takeIf(String::isNotBlank) }
            .filterNot { it.contains("header_logo") || it.contains("header-logo") }
            .distinct()
            .mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ---------------------------------------------------------------- Không dùng (đi qua fetch*)

    override fun popularMangaRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = throw UnsupportedOperationException()
    override fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()
    override fun mangaDetailsParse(response: Response): SManga = throw UnsupportedOperationException()

    // ---------------------------------------------------------------- Tiện ích

    /** Bỏ tên miền, giữ lại đường dẫn tương đối để URL sống được khi đổi domain. */
    private fun String.toPath(): String = when {
        isEmpty() -> ""
        startsWith("http") -> "/" + substringAfter("://").substringAfter('/')
        else -> this
    }

    companion object {
        private const val MANGA_URL = "/"
        private const val MANGA_TITLE = "One Punch Man"
        private const val CHAPTER_SLUG = "onepunch-man-chapter-"
        private const val MANGA_THUMBNAIL =
            "https://onepunchmantruyen.com/wp-content/uploads/2026/06/9784088844794.webp"
        private const val MANGA_DESCRIPTION =
            "Saitama là một anh hùng nghiệp dư chỉ vì sở thích. Sau ba năm luyện tập tới mức " +
                "rụng sạch tóc, anh mạnh tới nỗi hạ gục mọi đối thủ chỉ bằng MỘT cú đấm — và " +
                "chính điều đó khiến anh chán nản vì chẳng còn trận nào đáng để đánh.\n\n" +
                "Nguồn: onepunchmantruyen.com."

        /** Từ khoá ngắn hơn mức này chỉ được khớp trọn vẹn, tránh khớp bừa khi "Tìm mọi nguồn". */
        private const val MIN_PARTIAL_LEN = 3

        private val CHAPTER_NUMBER_REGEX =
            Regex("""onepunch-man-chapter-([0-9]+(?:[.-][0-9]+)?)""", RegexOption.IGNORE_CASE)

        /** Đã chuẩn hoá sẵn: chữ thường, bỏ dấu, một dấu cách. */
        private val ALIASES = listOf(
            "one punch man",
            "onepunch man",
            "one punch man truyen",
            "opm",
            "saitama",
            "wanpanman",
        )
    }
}

/** Bỏ dấu tiếng Việt, hạ chữ thường, gom khoảng trắng — để so từ khoá không phụ thuộc dấu. */
private fun String.normalizeForSearch(): String = Normalizer.normalize(this, Normalizer.Form.NFD)
    .replace(Regex("""\p{Mn}+"""), "")
    .replace('đ', 'd')
    .replace('Đ', 'D')
    .lowercase(Locale.ROOT)
    .replace(Regex("""[^a-z0-9]+"""), " ")
    .trim()
