package eu.kanade.tachiyomi.extension.vi.fapmanga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// Regex nhận diện link chương (2 tầng) để chắc chắn parseMangaList không lẫn chương vào danh sách bộ.
private val CHAPTER_URL_REGEX = Regex("""^https://fapmanga\.com/truyen-hentai/[^/]+/[^/]+/?$""")

class FapmangaParsersTest {

    private fun loadFixture(name: String): String {
        val stream = javaClass.getResourceAsStream("/$name")
        requireNotNull(stream) { "Không nạp được fixture $name qua classpath resource" }
        return stream.bufferedReader().readText()
    }

    // 4.1: danh-sach.html parse được, danh sách không rỗng.
    @Test
    fun `parseMangaList tra ve danh sach khong rong voi fixture danh-sach`() {
        val html = loadFixture("danh-sach.html")
        val page = parseMangaList(html, "getLatestUpdates", "https://fapmanga.com/danh-sach")
        assertTrue("Danh sách truyện phải có ít nhất một mục", page.mangas.size > 0)
    }

    // 4.2: fixture rỗng phải ném lỗi chứa cả functionName lẫn url truyền vào.
    @Test
    fun `parseMangaList nem loi khi fixture danh-sach-rong khong co truyen nao`() {
        val html = loadFixture("danh-sach-rong.html")
        val functionName = "parseMangaList-test-caller"
        val url = "https://fapmanga.com/danh-sach?page=1"

        val loi = assertThrows(Exception::class.java) {
            parseMangaList(html, functionName, url)
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

    // 4.3: mọi url kết quả phải là trang bộ 1 tầng, không phải chương (2 tầng) hay /the-loai/.
    @Test
    fun `parseMangaList chi lay url trang bo 1 tang khong lay chuong hay the-loai`() {
        val html = loadFixture("danh-sach.html")
        val page = parseMangaList(html, "getLatestUpdates", "https://fapmanga.com/danh-sach")

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
        }
    }

    // 4.4: không được có url trùng lặp trong kết quả.
    @Test
    fun `parseMangaList khong tra ve url trung lap`() {
        val html = loadFixture("danh-sach.html")
        val page = parseMangaList(html, "getLatestUpdates", "https://fapmanga.com/danh-sach")

        assertEquals(
            "Danh sách không được có url trùng lặp",
            page.mangas.size,
            page.mangas.distinctBy { it.url }.size,
        )

        // Ghi chú (mutation-check M2): fixture danh-sach.html không có link trùng ở sidebar/footer
        // (đã xác nhận bằng grep thủ công: đúng 24 href khớp mẫu, toàn bộ nằm trong .card-grid),
        // nên riêng phép cắt phạm vi không tự bộc lộ qua assert đếm trên fixture này. Thêm assert
        // đối chiếu độc lập với số thẻ .manga-card thật trong khối chính để ràng buộc rõ số lượng.
        val soTheThatTrongKhoiChinh = org.jsoup.Jsoup.parse(html).select(".manga-card").size
        assertEquals(
            "Số truyện parse được phải khớp đúng số thẻ .manga-card trong khối danh sách chính",
            soTheThatTrongKhoiChinh,
            page.mangas.size,
        )
    }

    // 4.5: fixture danh-sach.html có phân trang (link rel=next) nên hasNextPage phải là true.
    @Test
    fun `parseMangaList nhan dung hasNextPage khi fixture co phan trang`() {
        val html = loadFixture("danh-sach.html")
        val page = parseMangaList(html, "getLatestUpdates", "https://fapmanga.com/danh-sach")

        assertTrue("Fixture danh-sach.html có phân trang nên hasNextPage phải true", page.hasNextPage)
    }

    // 4.6: tên tiếng Việt có dấu phải được giải mã đúng, không bị mã hoá/lỗi encoding.
    @Test
    fun `parseMangaList giai ma dung ten truyen tieng Viet co dau`() {
        val html = loadFixture("danh-sach.html")
        val page = parseMangaList(html, "getLatestUpdates", "https://fapmanga.com/danh-sach")

        val tenThat = "Hệ Thống Thôi Miên Loạn Luân"
        val timThay = page.mangas.any { it.title == tenThat }

        assertTrue("Phải tìm thấy truyện có tên tiếng Việt có dấu: $tenThat", timThay)
    }
}
