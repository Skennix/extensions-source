package eu.kanade.tachiyomi.extension.vi.fapmanga

import eu.kanade.tachiyomi.source.model.MangasPage
import keiyoushi.source.KeiSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Test đo thật: chứng minh testCompileClasspath của module này có đủ lib keiyoushi
// (extensions-lib) và đường nạp fixture qua classpath resource, không còn là test JVM trivial.
class FapmangaSmokeTest {

    @Test
    fun `MangasPage tu extensions-lib bao hasNextPage dung nhu du lieu truyen vao`() {
        val page = MangasPage(emptyList(), false)
        assertFalse(page.hasNextPage)
    }

    @Test
    fun `Fapmanga la mot KeiSource`() {
        // Chú ý: annotation @Source có Retention SOURCE (xem core/keiyoushi/annotation/Source.kt),
        // nên nó KHÔNG còn tồn tại ở runtime để kiểm tra bằng reflection (Fapmanga::class.annotations
        // luôn rỗng dù biên dịch thành công). Kiểm tra quan hệ kế thừa thực sự với KeiSource thay vì
        // kiểm tra annotation, vì đây là thứ duy nhất còn lại ở runtime để xác nhận được.
        assertTrue(KeiSource::class.java.isAssignableFrom(Fapmanga::class.java))
    }

    @Test
    fun `fixture duoc nap qua classpath resource truoc khi co file HTML that`() {
        val stream = javaClass.getResourceAsStream("/README.md")
        assertNotNull("resources.setSrcDirs(test/fixtures) phai nap duoc README.md", stream)
        val content = stream!!.bufferedReader().readText()
        assertTrue(content.isNotBlank())
    }

    // T8: 23 thể loại trích tay từ danh-sach.html (đếm tay 2026-08-17: grep unique "/the-loai/<slug>"
    // trong fixture) + 1 mục "-- Tất cả --" mặc định = 24 lựa chọn. state=0 mặc định phải trỏ vào
    // mục "Tất cả" (slug rỗng), không được vô tình chọn sẵn 1 genre.
    @Test
    fun `GenreFilter co 24 lua chon va mac dinh la Tat ca voi slug rong`() {
        val filter = GenreFilter()

        assertEquals("23 thể loại + 1 mục Tất cả = 24 lựa chọn", 24, filter.values.size)
        assertEquals("Mặc định (state=0) phải là slug rỗng (không lọc)", "", filter.toUriPart())
    }
}
