package eu.kanade.tachiyomi.extension.vi.fapmanga

import eu.kanade.tachiyomi.source.model.MangasPage
import keiyoushi.source.KeiSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Test do that: chung minh testCompileClasspath cua module nay co du lib keiyoushi
// (extensions-lib) va duong nap fixture qua classpath resource, khong con la test JVM trivial.
class FapmangaSmokeTest {

    @Test
    fun `MangasPage tu extensions-lib bao hasNextPage dung nhu du lieu truyen vao`() {
        val page = MangasPage(emptyList(), false)
        assertFalse(page.hasNextPage)
    }

    @Test
    fun `Fapmanga la mot KeiSource`() {
        // Chu y: annotation @Source co Retention SOURCE (xem core/keiyoushi/annotation/Source.kt),
        // nen no KHONG con ton tai o runtime de kiem tra bang reflection (Fapmanga::class.annotations
        // luon rong du bien dich thanh cong). Kiem tra quan he ke thua thuc su voi KeiSource thay vi
        // kiem tra annotation, vi day la thu duy nhat con lai o runtime de xac nhan duoc.
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
