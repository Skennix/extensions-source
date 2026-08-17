package eu.kanade.tachiyomi.extension.vi.fapmanga

import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.serialization.json.JsonObject

// SChapter.create() (companion factory của extensions-lib) là hàm "Stub!" khi biên dịch/chạy
// ngoài app Mihon thật, giống lý do TestManga tồn tại. Dùng cài đặt tối giản của riêng test để
// tiêm vào parseChapterList qua tham số chapterFactory.
@Suppress("PropertyName")
class TestChapter : SChapter {
    override var url: String = ""
    override var name: String = ""
    override var chapter_number: Float = -1f
    override var scanlator: String? = null
    override var date_upload: Long = 0L
    override var memo: JsonObject = JsonObject(emptyMap())
}
