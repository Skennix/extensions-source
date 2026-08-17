package eu.kanade.tachiyomi.extension.vi.fapmanga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// Regex nhận diện link chương (2 tầng) để chắc chắn parseMangaList không lẫn chương vào danh sách bộ.
private val CHAPTER_URL_REGEX = Regex("""^https://fapmanga\.com/truyen-hentai/[^/]+/[^/]+/?$""")

private const val BASE_URL = "https://fapmanga.com"

class FapmangaParsersTest {

    private fun loadFixture(name: String): String {
        val stream = javaClass.getResourceAsStream("/$name")
        requireNotNull(stream) { "Không nạp được fixture $name qua classpath resource" }
        return stream.bufferedReader().readText()
    }

    // Mọi lời gọi test đều tiêm TestManga (lớp giả JVM), không dùng SManga.create() thật
    // (hàm "Stub!" ngoài app Mihon), để giữ hàm parseMangaList test được độc lập.
    private fun parse(html: String, functionName: String, url: String, baseUrl: String = BASE_URL) = parseMangaList(html, baseUrl, functionName, url) { TestManga() }

    private fun parseDetails(html: String, url: String, functionName: String = "parseMangaDetails-test-caller") = parseMangaDetails(html, functionName, url) { TestManga() }

    private fun parseChapters(html: String, functionName: String, url: String, baseUrl: String = BASE_URL) = parseChapterList(html, baseUrl, functionName, url) { TestChapter() }

    // 4.1: danh-sach.html parse được, danh sách không rỗng.
    @Test
    fun `parseMangaList tra ve danh sach khong rong voi fixture danh-sach`() {
        val html = loadFixture("danh-sach.html")
        val page = parse(html, "getLatestUpdates", "https://fapmanga.com/danh-sach")
        assertTrue("Danh sách truyện phải có ít nhất một mục", page.mangas.size > 0)
    }

    // 4.2: fixture rỗng phải ném lỗi chứa cả functionName lẫn url truyền vào.
    @Test
    fun `parseMangaList nem loi khi fixture danh-sach-rong khong co truyen nao`() {
        val html = loadFixture("danh-sach-rong.html")
        val functionName = "parseMangaList-test-caller"
        val url = "https://fapmanga.com/danh-sach?page=1"

        val loi = assertThrows(Exception::class.java) {
            parse(html, functionName, url)
        }

        assertTrue(
            "Thông báo lỗi phải chứa tên hàm gọi vào ($functionName)",
            loi.message?.contains(functionName) == true,
        )
        assertTrue(
            "Thông báo lỗi phải chứa url truyền vào ($url)",
            loi.message?.contains(url) == true,
        )
    }

    // 4.3: mọi url kết quả phải là trang bộ 1 tầng, không phải chương (2 tầng) hay /the-loai/,
    // và phải là url tương đối một tầng bắt đầu bằng /truyen-hentai/.
    @Test
    fun `parseMangaList chi lay url trang bo 1 tang khong lay chuong hay the-loai`() {
        val html = loadFixture("danh-sach.html")
        val page = parse(html, "getLatestUpdates", "https://fapmanga.com/danh-sach")

        page.mangas.forEach { manga ->
            val urlDayDu = "https://fapmanga.com" + manga.url
            assertFalse(
                "Không được lẫn url chương (2 tầng): $urlDayDu",
                CHAPTER_URL_REGEX.matches(urlDayDu),
            )
            assertFalse(
                "Không được lẫn url thể loại: $urlDayDu",
                urlDayDu.contains("/the-loai/"),
            )
            assertTrue(
                "manga.url phải là đường dẫn tương đối một tầng bắt đầu bằng /truyen-hentai/: ${manga.url}",
                manga.url.startsWith("/truyen-hentai/"),
            )
        }
    }

    // 4.4: không được có url trùng lặp trong kết quả, và số lượng phải khớp đúng số đếm tay.
    @Test
    fun `parseMangaList khong tra ve url trung lap`() {
        val html = loadFixture("danh-sach.html")
        val page = parse(html, "getLatestUpdates", "https://fapmanga.com/danh-sach")

        assertEquals(
            "Danh sách không được có url trùng lặp",
            page.mangas.size,
            page.mangas.distinctBy { it.url }.size,
        )

        // Đếm tay từ fixture 2026-08-17 (đúng 24 href khớp mẫu trong khối .card-grid chính,
        // không tính widget/sidebar). Đổi fixture thì đổi số này.
        val soTheDemTay = 24
        assertEquals(
            "Số truyện parse được phải khớp đúng số đếm tay từ khối danh sách chính",
            soTheDemTay,
            page.mangas.size,
        )
    }

    // 4.5: fixture danh-sach.html có phân trang (link rel=next) nên hasNextPage phải là true.
    @Test
    fun `parseMangaList nhan dung hasNextPage khi fixture co phan trang`() {
        val html = loadFixture("danh-sach.html")
        val page = parse(html, "getLatestUpdates", "https://fapmanga.com/danh-sach")

        assertTrue("Fixture danh-sach.html có phân trang nên hasNextPage phải true", page.hasNextPage)
    }

    // 4.6: tên tiếng Việt có dấu phải được giải mã đúng, không bị mã hoá/lỗi encoding.
    @Test
    fun `parseMangaList giai ma dung ten truyen tieng Viet co dau`() {
        val html = loadFixture("danh-sach.html")
        val page = parse(html, "getLatestUpdates", "https://fapmanga.com/danh-sach")

        val tenThat = "Hệ Thống Thôi Miên Loạn Luân"
        val timThay = page.mangas.any { it.title == tenThat }

        assertTrue("Phải tìm thấy truyện có tên tiếng Việt có dấu: $tenThat", timThay)
    }

    // 7.2: trang xếp hạng có widget "Truyện HOT" (6 link) và sidebar ".rank-list" (10 link) lặp
    // lại cùng slug với lưới chính (30 link) — phép cắt phạm vi phải loại cả hai, không chỉ giữ
    // lưới chính. Giao giữa 2 trang xếp hạng liền kề phải gần như rỗng (chỉ lưới chính đổi theo
    // trang, khác với widget/sidebar vốn không đổi theo trang và sẽ luôn trùng nhau).
    @Test
    fun `parseMangaList cat dung pham vi luoi chinh tren trang xep hang loai widget va sidebar`() {
        val p1 = parse(loadFixture("rank-week-p1.html"), "getPopularManga", "https://fapmanga.com?rank=week&page=1")
        val p2 = parse(loadFixture("rank-week-p2.html"), "getPopularManga", "https://fapmanga.com?rank=week&page=2")

        assertTrue("Trang 1 phải có ít nhất 25 mục (lưới chính thật, không lẫn thêm)", p1.mangas.size >= 25)
        assertTrue("Trang 2 phải có ít nhất 25 mục (lưới chính thật, không lẫn thêm)", p2.mangas.size >= 25)

        val urls1 = p1.mangas.map { it.url }.toSet()
        val urls2 = p2.mangas.map { it.url }.toSet()
        val giao = urls1.intersect(urls2)

        assertTrue(
            "Giao giữa 2 trang xếp hạng liền kề phải rất nhỏ (≤ 2), thực tế: ${giao.size} ($giao)",
            giao.size <= 2,
        )
    }

    // 4 (VỪA): trang cuối THẬT (đo 2026-08-17, rank=week&page=75) không phát rel="next" nữa
    // (rel=next đã biến mất trên trang cuối) nên hasNextPage phải là false.
    @Test
    fun `parseMangaList nhan dung hasNextPage false o trang cuoi that`() {
        val html = loadFixture("rank-week-cuoi.html")
        val page = parse(html, "getPopularManga", "https://fapmanga.com?rank=week&page=75")

        assertFalse("Trang cuối thật không còn rel=next nên hasNextPage phải false", page.hasNextPage)
    }

    // 4 (VỪA): trang giữa có cả prev lẫn next thì hasNextPage vẫn phải true.
    @Test
    fun `parseMangaList nhan dung hasNextPage true o trang giua co ca prev lan next`() {
        val html = loadFixture("danh-sach-p2.html")
        val page = parse(html, "getLatestUpdates", "https://fapmanga.com/danh-sach?page=2")

        assertTrue("Trang giữa có rel=next nên hasNextPage phải true", page.hasNextPage)
    }

    // 3 (VỪA): domain không được viết cứng trong hàm — regex dựng từ baseUrl truyền vào. Đổi
    // baseUrl (không khớp domain thật trong fixture) thì không còn tìm ra mục nào, phải ném lỗi.
    @Test
    fun `parseMangaList dung baseUrl truyen vao khong viet cung domain`() {
        val html = loadFixture("danh-sach.html")

        val loi = assertThrows(Exception::class.java) {
            parse(html, "getLatestUpdates", "https://mirror-khac.example/danh-sach", baseUrl = "https://mirror-khac.example")
        }

        assertTrue(
            "baseUrl khác domain thật trong fixture nên không khớp được mục nào, phải ném lỗi rỗng",
            loi.message?.contains("empty result") == true,
        )
    }

    // 5.1: fixture bo-truyen.html có 20 chương (đếm tay 2026-08-17: chap-2..chap-20 trừ chap-1
    // không tồn tại = 19 chương số + 1 chương đặt tên riêng "tessia-eralith-..." = 20), phải
    // tìm thấy url kết thúc bằng "/readers-paradise/chap-2".
    @Test
    fun `parseChapterList tra ve danh sach chuong khong rong voi fixture bo-truyen`() {
        val html = loadFixture("bo-truyen.html")
        val chapters = parseChapters(html, "parseChapterList", "https://fapmanga.com/truyen-hentai/readers-paradise")

        assertTrue("Bộ truyện phải có ít nhất một chương", chapters.isNotEmpty())
        assertTrue(
            "Phải chứa chương kết thúc bằng /readers-paradise/chap-2",
            chapters.any { it.url.endsWith("/readers-paradise/chap-2") },
        )
    }

    // 5.2: mọi url chương phải là url tương đối 2 tầng "/truyen-hentai/readers-paradise/<chuong>",
    // không được lẫn url 1 tầng (trang bộ, hay các bộ liên quan trong card-grid).
    @Test
    fun `parseChapterList chi lay url 2 tang khong lan url 1 tang`() {
        val html = loadFixture("bo-truyen.html")
        val chapters = parseChapters(html, "parseChapterList", "https://fapmanga.com/truyen-hentai/readers-paradise")

        val oneTierUrlRegex = Regex("""^/truyen-hentai/[^/?#]+/?$""")
        chapters.forEach { chapter ->
            assertTrue(
                "chapter.url phải là đường dẫn tương đối 2 tầng bắt đầu bằng /truyen-hentai/readers-paradise/: ${chapter.url}",
                chapter.url.startsWith("/truyen-hentai/readers-paradise/"),
            )
            assertFalse(
                "Không được lẫn url 1 tầng (trang bộ): ${chapter.url}",
                oneTierUrlRegex.matches(chapter.url),
            )
        }
    }

    // 5.3: không được có chương trùng url.
    @Test
    fun `parseChapterList khong tra ve url trung lap`() {
        val html = loadFixture("bo-truyen.html")
        val chapters = parseChapters(html, "parseChapterList", "https://fapmanga.com/truyen-hentai/readers-paradise")

        assertEquals(
            "Danh sách chương không được có url trùng lặp",
            chapters.size,
            chapters.distinctBy { it.url }.size,
        )
    }

    // 5.4: mới nhất trước = DOM đảo ngược (KHÔNG phải sort theo số chương). Đếm tay 2026-08-17:
    // khối .chapter-list có đúng 20 link, thứ tự DOM là chap-3,2,4,5,6,7,tessia,8,9,...,20 (site tự
    // xếp lạ 2 chương đầu "3 trước 2", CHẤP NHẬN làm chân lý). Đảo ngược DOM: [0]=chap-20 (đầu cuối
    // DOM, đồng thời cũng là số lớn nhất — trùng hợp không phải luật), và tessia phải nằm ngay sau
    // chap-8 (vì trong DOM gốc tessia đứng ngay TRƯỚC chap-8).
    @Test
    fun `parseChapterList sap chuong theo DOM dao nguoc, khong sort theo so`() {
        val html = loadFixture("bo-truyen.html")
        val chapters = parseChapters(html, "parseChapterList", "https://fapmanga.com/truyen-hentai/readers-paradise")

        assertEquals(
            "Đếm tay 2026-08-17: khối .chapter-list của fixture có đúng 20 link chương",
            20,
            chapters.size,
        )
        assertTrue(
            "Chương đầu tiên (mới nhất) phải kết thúc bằng /chap-20 (cuối DOM, đảo ngược lên đầu)",
            chapters[0].url.endsWith("/chap-20"),
        )

        val indexTessia = chapters.indexOfFirst { it.url.contains("tessia-eralith") }
        val indexChap8 = chapters.indexOfFirst { it.url.endsWith("/chap-8") }
        assertTrue("Phải tìm thấy chương tessia và chap-8 trong kết quả", indexTessia >= 0 && indexChap8 >= 0)
        assertEquals(
            "DOM gốc: tessia đứng ngay TRƯỚC chap-8 -> sau khi đảo ngược, tessia phải đứng ngay SAU chap-8",
            indexChap8 + 1,
            indexTessia,
        )
    }

    // 5.5: trang không có khối chương nào (fixture tự chế bo-truyen-rong.html — giữ tiêu đề
    // "<h2>Chương</h2>" nhưng bỏ hẳn div.chapter-list) phải ném lỗi chứa cả functionName lẫn url.
    @Test
    fun `parseChapterList nem loi khi fixture bo-truyen-rong khong co khoi chuong`() {
        val html = loadFixture("bo-truyen-rong.html")
        val functionName = "parseChapterList-test-caller"
        val url = "https://fapmanga.com/truyen-hentai/readers-paradise"

        val loi = assertThrows(Exception::class.java) {
            parseChapters(html, functionName, url)
        }

        assertTrue(
            "Thông báo lỗi phải chứa tên hàm gọi vào ($functionName)",
            loi.message?.contains(functionName) == true,
        )
        assertTrue(
            "Thông báo lỗi phải chứa url truyền vào ($url)",
            loi.message?.contains(url) == true,
        )
    }

    // 5.6: tên bộ có dấu tiếng Việt qua HTML entity (Reader&#039;s Paradise -> Reader's Paradise)
    // phải giải mã đúng. Riêng thể loại: fixture bo-truyen.html KHÔNG có link "/the-loai/" nào
    // nằm TRONG <article> (chỉ có ở header/footer dùng chung mọi trang) nên genre thật của bộ
    // này là null — dùng luôn description (đoạn duy nhất có dấu tiếng Việt thật trên trang) để
    // kiểm tra giải mã đúng, thay vì bịa ra một genre không tồn tại.
    @Test
    fun `parseMangaDetails giai ma dung ten bo va mo ta tieng Viet co dau, khong bia the loai`() {
        val html = loadFixture("bo-truyen.html")
        val manga = parseDetails(html, "https://fapmanga.com/truyen-hentai/readers-paradise")

        assertEquals("Reader's Paradise", manga.title)
        assertTrue(
            "Mô tả phải chứa đoạn tiếng Việt có dấu thật từ fixture",
            manga.description?.contains("Ở đây bạn sẽ đc chơi các nhân nữ xinh đẹp") == true,
        )
        assertTrue(
            "Fixture không có thể loại riêng cho bộ này trong <article>, genre phải là null (không bịa/không lẫn tag chân trang)",
            manga.genre.isNullOrBlank(),
        )
    }

    // NẶNG-1: bộ không có ảnh bìa (site render .manga-card-placeholder thay vì <img>, đo thật
    // 1/48 thẻ trong search-one.html) không được ăn nhầm bìa của bộ "Truyện liên quan" nào trong
    // cùng <article>. thumbnail_url phải là null, tuyệt đối không phải url ảnh của bộ liên quan.
    @Test
    fun `parseMangaDetails tra ve thumbnail null khi khong co bia, khong an nham anh bo lien quan`() {
        val html = loadFixture("bo-truyen-khong-bia.html")
        val manga = parseDetails(html, "https://fapmanga.com/truyen-hentai/readers-paradise")

        assertTrue(
            "Fixture không có og:image lẫn ảnh bìa nên thumbnail_url phải là null: ${manga.thumbnail_url}",
            manga.thumbnail_url == null,
        )
    }

    // NẶNG-2: title rỗng (fixture chế bo-truyen-title-rong.html — bỏ hẳn <h1>) phải ném lỗi chứa
    // cả functionName lẫn url, không được trả về SManga với title rỗng cho tầng trên.
    @Test
    fun `parseMangaDetails nem loi khi fixture khong co h1 nen title rong`() {
        val html = loadFixture("bo-truyen-title-rong.html")
        val functionName = "parseMangaDetails-test-caller"
        val url = "https://fapmanga.com/truyen-hentai/readers-paradise"

        val loi = assertThrows(Exception::class.java) {
            parseDetails(html, url, functionName)
        }

        assertTrue(
            "Thông báo lỗi phải chứa tên hàm gọi vào ($functionName)",
            loi.message?.contains(functionName) == true,
        )
        assertTrue(
            "Thông báo lỗi phải chứa url truyền vào ($url)",
            loi.message?.contains(url) == true,
        )
    }

    // VỪA-4: widget gợi ý 2 link CHƯƠNG của một bộ KHÁC (one-piece-series-truyen-ngan), nằm ngoài
    // div.chapter-list, không được lẫn vào kết quả — cả nhờ scope div.chapter-list lẫn regex neo
    // đúng slug "readers-paradise" trích từ url truyền vào.
    @Test
    fun `parseChapterList loc het link chuong cua bo khac tu widget goi y`() {
        val html = loadFixture("bo-truyen-widget-bo-khac.html")
        val chapters = parseChapters(html, "parseChapterList", "https://fapmanga.com/truyen-hentai/readers-paradise")

        assertEquals(
            "Kết quả phải vẫn đúng 20 chương của readers-paradise, không lẫn 2 link chương bộ khác",
            20,
            chapters.size,
        )
        assertTrue(
            "Không được lẫn link chương nào của bộ khác (one-piece-series-truyen-ngan)",
            chapters.none { it.url.contains("one-piece-series-truyen-ngan") },
        )
    }

    // VỪA-5: fixture chế bo-truyen-co-genre.html thêm 2 link "/the-loai/" thật của bộ này (Hentai,
    // Manhwa) vào khối info trong <article> — genre phải bóc tách được, nối bằng ", ".
    @Test
    fun `parseMangaDetails boc dung the loai khi co genre that trong article`() {
        val html = loadFixture("bo-truyen-co-genre.html")
        val manga = parseDetails(html, "https://fapmanga.com/truyen-hentai/readers-paradise")

        assertEquals("Hentai, Manhwa", manga.genre)
    }
}
