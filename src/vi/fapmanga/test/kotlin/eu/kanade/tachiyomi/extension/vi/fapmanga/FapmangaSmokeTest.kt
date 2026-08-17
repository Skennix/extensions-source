package eu.kanade.tachiyomi.extension.vi.fapmanga

import eu.kanade.tachiyomi.source.model.MangasPage
import keiyoushi.source.KeiSource
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
}
