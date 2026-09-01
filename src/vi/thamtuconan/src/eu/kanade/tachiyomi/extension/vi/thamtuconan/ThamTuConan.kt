package eu.kanade.tachiyomi.extension.vi.thamtuconan

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import java.text.Normalizer
import java.util.Locale
import kotlin.time.Instant

/**
 * Nguồn DẠNG MỘT BỘ: blog chỉ đăng đúng một tác phẩm (Thám tử lừng danh Conan).
 *
 * 🔴 Hợp đồng "không gọi mạng":
 *   - [fetchPopularManga], [fetchLatestUpdates], [fetchSearchManga], [fetchMangaDetails]
 *     trả về HẰNG SỐ trong mã, KHÔNG phát một request nào.
 *     Lý do: app Xưởng Truyện có chức năng "Tìm mọi nguồn" bắn getSearchManga tới MỌI
 *     nguồn đang cài (đo được 79–138 nguồn). Một nguồn một-bộ mà gọi mạng ở đây thì mọi
 *     lượt tìm đều tốn thêm một request rác tới một site chỉ có đúng một truyện.
 *   - Chỉ [fetchChapterList] và [pageListParse] mới được đụng tới mạng.
 */
@Source
abstract class ThamTuConan : HttpSource() {

    override val supportsLatest = true

    // ---------------------------------------------------------------- Hằng số của bộ truyện

    private val theManga: SManga
        get() = SManga.create().apply {
            url = MANGA_URL
            title = MANGA_TITLE
            author = "Gosho Aoyama"
            artist = "Gosho Aoyama"
            status = SManga.ONGOING
            genre = "Trinh thám, Hành động, Hài hước, Shounen"
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
     * Nguồn danh sách chương: feed JSON của Blogger `/feeds/posts/default?alt=json`.
     * Feed trả tối đa 150 bài mỗi lượt (đo 2026-09-01: xin 500 vẫn chỉ trả 150),
     * nên phải cuốn `start-index` cho tới khi hết.
     *
     * 🔴 DỪNG THEO `openSearch$totalResults`, KHÔNG dừng khi gặp trang ngắn.
     * Đo 2026-09-01 trên chính blog này — feed trả trang ngắn GIỮA CHỪNG:
     * ```
     * start-index=  1 -> 150 | start-index=451 ->  76  <-- ngan nhung CHUA het
     * start-index=151 -> 150 | start-index=527 -> 150
     * start-index=301 -> 150 | start-index=677 ->  40 | start-index=717 -> 0
     * ```
     * Dừng ở trang ngắn đầu tiên thì chỉ lấy được 526/716 chương — mất 190 chương
     * (26%) TRONG IM LẶNG, danh sách vẫn hiện ra nên nhìn qua tưởng vẫn ổn.
     *
     * 🔴 Feed BỊ CẮT NỘI DUNG (~337 byte/bài, đúng 1 ảnh) ⇒ chỉ dùng được cho danh sách
     * chương, KHÔNG lấy được ảnh từng trang. Ảnh phải nạp từ trang bài — xem [pageListParse].
     */
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val chapters = mutableListOf<SChapter>()
        var startIndex = 1
        var totalEntries = MAX_FEED_ENTRIES

        while (startIndex <= minOf(totalEntries, MAX_FEED_ENTRIES)) {
            // 🔴 ĐỪNG đặt tên biến này là `url`: nó sẽ che `SChapter.url` bên trong khối
            // `apply` phía dưới (Kotlin ưu tiên biến cục bộ hơn thành viên của receiver).
            val feedUrl = "$baseUrl/feeds/posts/default?alt=json" +
                "&max-results=$FEED_PAGE_SIZE&start-index=$startIndex"

            val feed = client.newCall(GET(feedUrl, headers)).execute().use { response ->
                if (!response.isSuccessful) throw Exception("HTTP error ${response.code}")
                response.parseAs<BloggerFeedRoot>().feed
            }
            val entries = feed.entry.orEmpty()

            feed.totalResults?.text?.toIntOrNull()?.let { totalEntries = it }

            // Chỉ trang RỖNG mới là hết — trang ngắn KHÔNG phải (xem ghi chú trên).
            if (entries.isEmpty()) break

            entries.mapTo(chapters) { entry ->
                SChapter.create().apply {
                    val href = entry.link
                        .firstOrNull { it.rel == "alternate" }?.href
                        .orEmpty()
                    url = href.toPath()
                    name = entry.title.text.trim()
                    chapter_number = CHAPTER_NUMBER_REGEX.find(name)
                        ?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
                    date_upload = Instant.tryParse(entry.published?.text)
                }
            }

            startIndex += entries.size
        }

        chapters.filter { it.url.isNotEmpty() }.distinctBy { it.url }
    }

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    // ---------------------------------------------------------------- Trang ảnh (CÓ gọi mạng)

    /**
     * Ảnh nằm trong `div.post-body.entry-content` của trang bài.
     *
     * ⚠️ KHÔNG khoá cứng theo host CDN. Đo 2026-09-01 trên chính blog này:
     *   - bài mới  → `cdn.kenhsinhvien.vn`
     *   - bài cũ   → `blogger.googleusercontent.com`
     * Khoá theo host thì bài cũ sẽ ra 0 trang trong im lặng. Cắt theo KHỐI NỘI DUNG
     * mới đúng, và khối đó cũng không lẫn ảnh trang trí/quảng cáo (đo: 15/15 và 114/114).
     */
    override fun pageListParse(response: Response): List<Page> {
        val body = response.asJsoup().selectFirst("div.post-body.entry-content")
            ?: return emptyList()

        return body.select("img[src]")
            .mapNotNull { it.attr("abs:src").takeIf(String::isNotBlank) }
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
    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()

    // ---------------------------------------------------------------- Tiện ích

    /** Bỏ tên miền, giữ lại đường dẫn tương đối để URL sống được khi đổi domain. */
    private fun String.toPath(): String = when {
        isEmpty() -> ""
        startsWith("http") -> "/" + substringAfter("://").substringAfter('/')
        else -> this
    }

    companion object {
        private const val MANGA_URL = "/"
        private const val MANGA_TITLE = "Thám Tử Lừng Danh Conan"
        private const val MANGA_THUMBNAIL =
            "https://blogger.googleusercontent.com/img/b/R29vZ2xl/AVvXsEgb2IjRTJNp00ARd3mVXzJQ" +
                "FqAaEmiy1Ff8cG-Zg0mY-zGtnf5CMW_WGZCaAu_KiREJJclmDorqqCVBQVKgSkk1SYTQ4J_9frAyNzEI" +
                "jMD03hIjf_EUcD94xC3xbsvgsfoyrywZBLlIkxGyzEC3kCR1HohoRcjJpkqvar4STXVKS6iuh7Rf4GHF" +
                "9Pe4nQ/s1600/doc_truyen_tranh_conan_chap_1_2.jpg"
        private const val MANGA_DESCRIPTION =
            "Thám tử học sinh trung học Kudo Shinichi bị tổ chức Áo Đen ép uống một loại thuốc độc " +
                "khiến cơ thể teo nhỏ lại thành một cậu bé tiểu học. Mang tên giả Edogawa Conan, " +
                "cậu vừa sống nhờ nhà thám tử Mori Kogoro vừa lần theo dấu vết tổ chức để tìm cách " +
                "trở lại hình dạng cũ.\n\n" +
                "Nguồn: blog thamtu-conan.blogspot.com (ảnh nằm trên cdn.kenhsinhvien.vn và " +
                "blogger.googleusercontent.com)."

        /** Blogger trả tối đa 150 bài mỗi lượt bất kể xin bao nhiêu (đo 2026-09-01). */
        private const val FEED_PAGE_SIZE = 150

        /** Chặn trên để vòng lặp không bao giờ chạy vô hạn nếu feed trả lạ. */
        private const val MAX_FEED_ENTRIES = 5000

        /** Từ khoá ngắn hơn mức này chỉ được khớp trọn vẹn, tránh khớp bừa khi "Tìm mọi nguồn". */
        private const val MIN_PARTIAL_LEN = 3

        private val CHAPTER_NUMBER_REGEX = Regex("""chap\s*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)

        /** Đã chuẩn hoá sẵn: chữ thường, bỏ dấu, một dấu cách. */
        private val ALIASES = listOf(
            "tham tu lung danh conan",
            "tham tu conan",
            "conan",
            "detective conan",
            "case closed",
            "meitantei conan",
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

// ---------------------------------------------------------------- Mô hình feed Blogger

@Serializable
class BloggerFeedRoot(
    val feed: BloggerFeed,
)

@Serializable
class BloggerFeed(
    val entry: List<BloggerEntry>? = null,
    @SerialName("openSearch\$totalResults") val totalResults: BloggerText? = null,
)

@Serializable
class BloggerEntry(
    val title: BloggerText,
    val published: BloggerText? = null,
    val link: List<BloggerLink> = emptyList(),
)

@Serializable
class BloggerText(
    @SerialName("\$t") val text: String,
)

@Serializable
class BloggerLink(
    val rel: String = "",
    val href: String = "",
)
