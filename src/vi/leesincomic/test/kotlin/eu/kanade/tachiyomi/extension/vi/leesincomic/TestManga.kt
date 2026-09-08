package eu.kanade.tachiyomi.extension.vi.leesincomic

import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.json.JsonObject

// SManga.create() là hàm "Stub!" khi chạy ngoài app Mihon thật (app cấp implementation lúc
// runtime, unit test JVM thuần không có). Tiêm cài đặt tối giản này qua tham số mangaFactory,
// thay vì để production tự giữ một lớp giả không bao giờ dùng lúc chạy thật.
@Suppress("PropertyName")
class TestManga : SManga {
    override var url: String = ""
    override var title: String = ""
    override var thumbnail_url: String? = null
    override var artist: String? = null
    override var author: String? = null
    override var status: Int = SManga.UNKNOWN
    override var description: String? = null
    override var genre: String? = null
    override var update_strategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE
    override var memo: JsonObject = JsonObject(emptyMap())
    override var initialized: Boolean = false
}
