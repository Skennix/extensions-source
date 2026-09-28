package eu.kanade.tachiyomi.extension.vi.damconuong

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.time.Instant
import java.time.format.DateTimeParseException

// BACKLOG (153) — site đổi hẳn sang API JSON riêng (api.damconuong.pw/api/v1), thay cho scrape
// HTML cũ. Mọi lượt gọi list đều bọc trong ApiListResponse (có phân trang), lượt gọi một bản ghi
// bọc trong ApiSingleResponse — cùng một khuôn `{"success", "message", "data"}` cho mọi endpoint
// đã đo được.

@Serializable
class ApiListResponse<T>(
    val data: List<T>,
    val meta: ApiMeta? = null,
)

@Serializable
class ApiSingleResponse<T>(val data: T)

@Serializable
class ApiMeta(val pagination: ApiPagination? = null)

@Serializable
class ApiPagination(
    @SerialName("current_page") val currentPage: Int,
    @SerialName("last_page") val lastPage: Int,
)

@Serializable
class GenreDto(val name: String)

@Serializable
class MangaDto(
    private val name: String,
    private val slug: String,
    private val pilot: String? = null,
    private val status: Int? = null,
    @SerialName("cover_full_url") private val coverFullUrl: String? = null,
    private val genres: List<GenreDto> = emptyList(),
) {
    fun toSManga() = SManga.create().apply {
        url = slug
        title = name
        thumbnail_url = coverFullUrl
        genre = genres.joinToString { it.name }.ifEmpty { null }
        description = pilot?.parseHtmlText()?.ifEmpty { null }
        status = when (this@MangaDto.status) {
            1 -> SManga.ONGOING
            2 -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }
}

@Serializable
class ChapterDto(
    private val name: String,
    private val slug: String,
    @SerialName("created_at") private val createdAt: String? = null,
) {
    fun toSChapter(mangaSlug: String) = SChapter.create().apply {
        url = "$mangaSlug/$slug"
        name = this@ChapterDto.name
        date_upload = createdAt?.parseIsoDateOrZero() ?: 0L
    }
}

private fun String.parseHtmlText(): String = Jsoup.parse(this, "", Parser.htmlParser()).wholeText().trim()

private fun String.parseIsoDateOrZero(): Long = try {
    Instant.parse(this).toEpochMilli()
} catch (e: DateTimeParseException) {
    0L
}
