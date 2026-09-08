package eu.kanade.tachiyomi.extension.vi.leesincomic

import eu.kanade.tachiyomi.source.model.SManga
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val GOC = "https://leesincomic.com"
private const val URL_TRANG_1 = "$GOC/top-thang.html?page=1"
private const val URL_TRANG_3 = "$GOC/top-thang.html?page=3"
private const val URL_TRANG_CUOI = "$GOC/top-thang.html?page=30"
private const val URL_TIM_RONG = "$GOC/tim-kiem.html?key=zzzqqq&page=1"
private const val URL_TIM_CO = "$GOC/tim-kiem.html?key=ngu&page=1"
private const val URL_BO = "$GOC/truyen-tranh/nguc-tu.html"
private const val URL_CHUONG = "$GOC/truyen-tranh/nguc-tu/20-end.html"

/**
 * Fixture chụp 2026-09-08 — xem `test/fixtures/README.md`. Mỗi ca dưới đây gắn với đúng một luật
 * của skill `new-extension` Phase 4; cột "phép phá" ghi trong docstring của từng ca.
 */
class LeeSinComicParsersTest {

    private fun fixture(ten: String): String {
        val stream = javaClass.getResourceAsStream("/$ten")
        requireNotNull(stream) { "Không nạp được fixture $ten qua classpath resource" }
        return stream.bufferedReader().readText()
    }

    private fun danhSach(ten: String, url: String, functionName: String = "test-caller", allowEmpty: Boolean = false) = parseMangaList(fixture(ten), functionName, url, allowEmpty) { TestManga() }

    private fun chiTiet(ten: String, url: String, functionName: String = "test-caller") = parseMangaDetails(fixture(ten), functionName, url) { TestManga() }

    private fun chuong(ten: String, url: String, functionName: String = "test-caller") = parseChapterList(fixture(ten), functionName, url) { TestChapter() }

    private fun anh(ten: String, url: String, functionName: String = "test-caller") = parsePageList(fixture(ten), functionName, url)

    // ── Danh sách ───────────────────────────────────────────────────────────

    /** Ca DƯƠNG cơ sở: số đo thật là 48 mục/trang. Assert số lượng, không assert "không ném". */
    @Test
    fun `danh sach trang 1 tra ve dung 48 muc`() {
        val trang = danhSach("danh-sach-trang-1.html", URL_TRANG_1)
        assertEquals("Trang danh sách phải có đúng 48 mục (đo thật 2026-09-08)", 48, trang.mangas.size)
        assertTrue("Còn trang sau", trang.hasNextPage)
    }

    /**
     * 🔴 Guard CẮT PHẠM VI. Phép phá: đổi `document.select(CUM_KET_QUA)` thành `listOf(document)`
     * (quét cả tài liệu) ⇒ trang 1 vẫn 48 nhưng trang 3 thành 67 và GIAO thành 19 (widget
     * "Top ngày/tuần/tháng"). Ca này phải đỏ.
     */
    @Test
    fun `hai trang lien tiep khong lan mot muc nao`() {
        val t1 = danhSach("danh-sach-trang-1.html", URL_TRANG_1).mangas.map { it.url }.toSet()
        val t3 = danhSach("danh-sach-trang-giua.html", URL_TRANG_3).mangas.map { it.url }.toSet()

        assertEquals("Trang 3 cũng phải có 48 mục", 48, t3.size)
        assertEquals(
            "Trang 1 và trang 3 không được lẫn mục nào — lẫn nghĩa là đang quét cả widget",
            emptySet<String>(),
            t1 intersect t3,
        )
    }

    /** Trong MỘT trang cũng không được trùng (mỗi ô có hai thẻ `<a>`, dễ đếm hai lần). */
    @Test
    fun `mot trang khong co muc trung nhau`() {
        val ds = danhSach("danh-sach-trang-1.html", URL_TRANG_1).mangas.map { it.url }
        assertEquals("Không được có URL trùng trong cùng một trang", ds.size, ds.toSet().size)
    }

    /**
     * 🔴 Guard MẪU URL. Mỗi ô có HAI thẻ `<a>`: link bộ (1 segment) và link chương mới nhất
     * (2 segment). Phép phá: nới `MAU_DUONG_BO` thành `/truyen-tranh/.+\.html` ⇒ link chương lọt.
     */
    @Test
    fun `moi muc tro toi trang BO chu khong phai trang CHUONG`() {
        val ds = danhSach("danh-sach-trang-1.html", URL_TRANG_1).mangas
        val mauBo = Regex("""^/truyen-tranh/[^/?#]+\.html$""")
        val sai = ds.map { it.url }.filterNot { mauBo.matches(it) }
        assertEquals("Mọi mục phải là URL trang bộ (1 segment sau /truyen-tranh/)", emptyList<String>(), sai)
    }

    /** Ca DƯƠNG cho tiêu đề + ảnh bìa: assert âm (`isNotBlank`) một mình thì xoá code vẫn xanh. */
    @Test
    fun `moi muc co ten va anh bia`() {
        val ds = danhSach("danh-sach-trang-1.html", URL_TRANG_1).mangas
        assertEquals("Mọi mục phải có tên", emptyList<String>(), ds.filter { it.title.isBlank() }.map { it.url })
        assertEquals(
            "Mọi mục phải có ảnh bìa",
            emptyList<String>(),
            ds.filter { it.thumbnail_url.isNullOrBlank() }.map { it.url },
        )
        assertTrue(
            "Ảnh bìa phải là URL tuyệt đối tới kho ảnh",
            ds.all { laAnhTruyen(it.thumbnail_url!!) },
        )
    }

    /**
     * 🔴 Guard RỖNG-NÉM-LỖI. Fixture `?page=30` chỉ còn `Không còn dữ liệu` trong khối kết quả —
     * nhưng VẪN mang 19 mục widget, nên ca này cũng là mẫu cố tình dương cho phép cắt phạm vi:
     * bỏ cắt phạm vi thì nó trả 19 mục và KHÔNG ném.
     */
    @Test
    fun `trang het du lieu thi nem loi kem ten ham va url`() {
        val ten = "phanTichDanhSach-test-caller"
        val loi = assertThrows(Exception::class.java) {
            danhSach("danh-sach-trang-cuoi.html", URL_TRANG_CUOI, functionName = ten)
        }
        assertTrue("Thông báo lỗi phải chứa tên hàm ($ten)", loi.message?.contains(ten) == true)
        assertTrue("Thông báo lỗi phải chứa URL đã gọi", loi.message?.contains(URL_TRANG_CUOI) == true)
    }

    /**
     * NGOẠI LỆ DUY NHẤT: tìm kiếm CÓ TỪ KHOÁ mà 0 kết quả là hợp lệ ⇒ trả rỗng, KHÔNG ném, và
     * `hasNextPage` phải là `false` (nếu không thì app duyệt trang vô tận).
     */
    @Test
    fun `tim kiem khong ra ket qua thi tra rong chu khong nem`() {
        val trang = danhSach("tim-kiem-rong.html", URL_TIM_RONG, allowEmpty = true)
        assertEquals("Tìm kiếm không ra gì phải trả 0 mục (không phải 19 mục widget)", 0, trang.mangas.size)
        assertFalse("Trang rỗng thì không còn trang sau", trang.hasNextPage)
    }

    /** Tìm kiếm CÓ kết quả vẫn phải qua đúng phép cắt phạm vi. */
    @Test
    fun `tim kiem co ket qua tra ve 48 muc`() {
        val trang = danhSach("tim-kiem-co-ket-qua.html", URL_TIM_CO, allowEmpty = true)
        assertEquals(48, trang.mangas.size)
        assertTrue(trang.hasNextPage)
    }

    // ── Trang bộ ────────────────────────────────────────────────────────────

    @Test
    fun `trang bo doc duoc ten trang thai the loai va mo ta`() {
        val bo = chiTiet("trang-bo.html", URL_BO)
        assertEquals("Ngục Tù", bo.title)
        assertEquals("`Tình trang: Full` phải đọc thành COMPLETED", SManga.COMPLETED, bo.status)
        assertTrue("Thể loại phải có Manhwa", bo.genre!!.contains("Manhwa"))
        assertTrue("Ảnh bìa phải trỏ vào kho ảnh", laAnhTruyen(bo.thumbnail_url!!))
        assertTrue("Mô tả phải mang tên khác", bo.description!!.contains("THE CELL"))
        assertTrue("Mô tả phải mang nhóm dịch", bo.description!!.contains("Nhóm dịch: Lessin Comic"))
        assertTrue("Mô tả phải mang nội dung giới thiệu", bo.description!!.contains("Choi Junsu"))
    }

    /** Nhóm dịch KHÔNG phải tác giả — ghi vào `author` là ghi dữ liệu sai vào metadata. */
    @Test
    fun `nhom dich khong duoc ghi vao truong tac gia`() {
        val bo = chiTiet("trang-bo.html", URL_BO)
        assertTrue("author phải để rỗng vì site không có trường tác giả", bo.author.isNullOrBlank())
    }

    @Test
    fun `trang khong phai trang bo thi nem loi`() {
        val ten = "mangaDetailsParse-test-caller"
        val loi = assertThrows(Exception::class.java) {
            chiTiet("danh-sach-trang-1.html", URL_TRANG_1, functionName = ten)
        }
        assertTrue(loi.message?.contains(ten) == true)
    }

    // ── Danh sách chương ────────────────────────────────────────────────────

    @Test
    fun `danh sach chuong tra ve dung 20 chuong cua dung bo do`() {
        val ds = chuong("trang-bo.html", URL_BO)
        assertEquals("Fixture nguc-tu có 20 chương", 20, ds.size)
        val laBoKhac = ds.map { it.url }.filterNot { it.startsWith("/truyen-tranh/nguc-tu/") }
        assertEquals("Không được lẫn chương của bộ khác", emptyList<String>(), laBoKhac)
    }

    /**
     * 🔴 Guard NEO SLUG BỘ. Fixture TỰ CHẾ: `trang-bo-lan-chuong-bo-khac.html` là bản sao của
     * `trang-bo.html` cộng đúng MỘT `div.chapter-item` trỏ sang bộ khác
     * (`/truyen-tranh/mot-bo-khac/5.html`), đặt ở đầu danh sách.
     *
     * Vì sao phải tự chế: đo 2026-09-08 trên fixture thật, phép phá "bỏ neo slug bộ" KHÔNG làm ca
     * nào đỏ — trang bộ có thật không hề chứa link chương của bộ khác. Guard xanh vì lý do sai.
     * Phép phá: đổi điều kiện neo thành `if (false)` ⇒ ca này phải đỏ (21 chương thay vì 20).
     */
    @Test
    fun `chuong cua bo khac khong lot vao danh sach`() {
        val ds = chuong("trang-bo-lan-chuong-bo-khac.html", URL_BO)
        assertEquals("Vẫn phải là 20 chương của nguc-tu, không phải 21", 20, ds.size)
        assertEquals(
            "Không được lẫn chương của bộ khác",
            emptyList<String>(),
            ds.map { it.url }.filter { it.contains("mot-bo-khac") },
        )
    }

    /**
     * 🔴 Guard THỨ TỰ. DOM đã là mới-nhất-trước. Phép phá: `sortedBy { it.chapter_number }` ⇒
     * chương `1` nhảy lên đầu. Ca này phải đỏ.
     */
    @Test
    fun `thu tu chuong giu nguyen thu tu DOM moi nhat truoc`() {
        val ds = chuong("trang-bo.html", URL_BO)
        assertEquals("Chương đầu danh sách phải là chương mới nhất", "20 -End", ds.first().name)
        assertEquals("Chương cuối danh sách phải là chương cũ nhất", "1", ds.last().name)
        assertNotEquals(
            "Danh sách KHÔNG được sắp tăng dần theo số chương",
            ds.map { it.chapter_number }.sorted(),
            ds.map { it.chapter_number },
        )
    }

    @Test
    fun `chuong doc duoc so chuong va ngay dang`() {
        val ds = chuong("trang-bo.html", URL_BO)
        assertEquals("`20 -End` phải cho số chương 20", 20f, ds.first().chapter_number, 0f)
        assertEquals("`1` phải cho số chương 1", 1f, ds.last().chapter_number, 0f)
        // Mốc TUYỆT ĐỐI, không phải `> 0`: `> 0` vẫn xanh khi đọc nhầm định dạng ngày (đo
        // 2026-09-08: đổi sang `MM/dd/yyyy` thì SimpleDateFormat lenient cuộn `29/10/2025` thành
        // tháng 29 ⇒ ra 2027, vẫn `> 0` và vẫn "mới hơn chương cũ"). 29/10/2025 00:00 giờ VN:
        assertEquals("Ngày chương mới nhất phải là 29/10/2025 giờ VN", 1761670800000L, ds.first().date_upload)
        assertEquals("Ngày chương cũ nhất phải là 11/07/2025 giờ VN", 1752166800000L, ds.last().date_upload)
        assertTrue(
            "Chương mới nhất phải mới hơn chương cũ nhất",
            ds.first().date_upload > ds.last().date_upload,
        )
    }

    @Test
    fun `trang khong co danh sach chuong thi nem loi`() {
        val ten = "chapterListParse-test-caller"
        val loi = assertThrows(Exception::class.java) {
            chuong("trang-chuong.html", URL_CHUONG, functionName = ten)
        }
        assertTrue(loi.message?.contains(ten) == true)
    }

    // ── Trang ảnh ───────────────────────────────────────────────────────────

    /**
     * 🔴 Guard `data-src`. Phép phá: đọc `src` thay vì `data-src` ⇒ mọi ảnh thành
     * `/images/loadx-min.gif`, bộ lọc `uploads/minh_hoa/` loại sạch ⇒ ném lỗi. Ca này phải đỏ.
     *
     * 🔴 Guard LỌC KHO ẢNH. Phép phá: bỏ `.filter { laAnhTruyen(it) }` ⇒ 33 thành 33 (fixture này
     * chỉ có ảnh truyện mang `data-src`), nên ca đó được canh bằng `moi anh deu nam trong kho anh`
     * cộng với số đo 36 thẻ `<img>` ghi trong docstring parser.
     */
    @Test
    fun `trang chuong tra ve dung 33 anh dung thu tu`() {
        val ds = anh("trang-chuong.html", URL_CHUONG)
        assertEquals("Fixture chương 20-end có 33 ảnh", 33, ds.size)
        assertEquals("Ảnh đầu phải là trang 1", 0, ds.first().index)
        assertTrue("Ảnh đầu phải là tệp `1-`", ds.first().imageUrl!!.contains("/1-"))
        assertTrue("Ảnh cuối phải là tệp `33-`", ds.last().imageUrl!!.contains("/33-"))
        assertEquals("Chỉ số trang phải liên tục từ 0", (0 until 33).toList(), ds.map { it.index })
    }

    @Test
    fun `moi anh deu nam trong kho anh va khong phai anh cho lazyload`() {
        val ds = anh("trang-chuong.html", URL_CHUONG)
        assertEquals(
            "Mọi ảnh phải mang đoạn đường dẫn kho ảnh",
            emptyList<String>(),
            ds.map { it.imageUrl!! }.filterNot { laAnhTruyen(it) },
        )
        assertEquals(
            "Không được lẫn ảnh chờ lazyload",
            emptyList<String>(),
            ds.map { it.imageUrl!! }.filter { it.contains("loadx") },
        )
    }

    @Test
    fun `trang khong co anh truyen thi nem loi`() {
        val ten = "pageListParse-test-caller"
        val loi = assertThrows(Exception::class.java) {
            anh("trang-bo.html", URL_BO, functionName = ten)
        }
        assertTrue(loi.message?.contains(ten) == true)
    }

    // ── Hàm phụ ─────────────────────────────────────────────────────────────

    @Test
    fun `slug bo lay duoc tu ca url bo lan url chuong`() {
        assertEquals("nguc-tu", slugBo(URL_BO))
        assertEquals("nguc-tu", slugBo(URL_CHUONG))
        assertEquals("19+-khoai-cam-toi-loi", slugBo("$GOC/truyen-tranh/19+-khoai-cam-toi-loi.html"))
    }

    @Test
    fun `bo domain giu duong dan va bo host`() {
        assertEquals("/truyen-tranh/nguc-tu.html", boDomain(URL_BO))
        assertEquals("/tim-kiem.html?key=ngu&page=1", boDomain(URL_TIM_CO))
    }
}
