package eu.kanade.tachiyomi.extension.vi.damconuong

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

// BACKLOG (153) — site viết lại hoàn toàn trên nền tảng mới (2026-09-28). Genre-picker cũ
// (fetchFilterData qua nút Alpine.js @click="toggleGenre(...)") không còn tồn tại, và lọc theo
// thể loại qua query (`genre=`/`filter[genre]=`/`filter[genres]=`) đều trả 400 ở API mới — CHƯA
// tìm ra cú pháp đúng. Bỏ genre-filter, chỉ giữ Trạng thái (đã đo trực tiếp: filter[status]=2 lọc
// đúng, khớp badge "Hoàn thành" trên trang chi tiết) và Sắp xếp (mangas/hot = phổ biến,
// mangas/recent = mới cập nhật, đã có sẵn hai lối vào riêng nên không cần Sort filter nữa).
fun getFilters(): FilterList = FilterList(
    Filter.Header("Bộ lọc bị bỏ qua khi tìm theo tên"),
    StatusFilter(),
)

class StatusFilter :
    UriPartFilter(
        "Tình trạng",
        listOf(
            Option("Đang tiến hành", "1"),
            Option("Hoàn thành", "2"),
        ),
    )

class Option(val name: String, val value: String)

open class UriPartFilter(
    displayName: String,
    options: List<Option>,
) : Filter.Select<String>(displayName, (listOf("Tất cả") + options.map { it.name }).toTypedArray()) {
    private val uriParts = listOf<String?>(null) + options.map { it.value }

    fun toUriPart(): String? = uriParts[state]
}
