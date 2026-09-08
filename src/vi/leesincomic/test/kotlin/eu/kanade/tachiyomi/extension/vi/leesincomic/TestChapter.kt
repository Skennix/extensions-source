package eu.kanade.tachiyomi.extension.vi.leesincomic

import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.serialization.json.JsonObject

// SChapter.create() là hàm "Stub!" ngoài app Mihon thật, cùng lý do TestManga tồn tại.
@Suppress("PropertyName")
class TestChapter : SChapter {
    override var url: String = ""
    override var name: String = ""
    override var chapter_number: Float = -1f
    override var scanlator: String? = null
    override var date_upload: Long = 0L
    override var memo: JsonObject = JsonObject(emptyMap())
}
