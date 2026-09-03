package eu.kanade.tachiyomi.extension.vi.manhuavn

import eu.kanade.tachiyomi.source.model.Filter

/** Mot lua chon co nhan hien thi va gia tri dua vao URL. */
// `val` la bat buoc: tham so ham dung khong co `val` chi song trong khoi khoi tao,
// khong dung duoc trong than mot ham thanh vien nhu toString(). Bo `val` => build do
// "Unresolved reference 'name'". Da tra gia mot luot build 2026-09-04.
class UriPart(val name: String, val value: String) {
    override fun toString(): String = name
}

/**
 * The loai -> duong dan /the-loai/{slug}.html
 *
 * Danh sach lay tu chinh menu cua site (do 2026-09-03, 19 muc).
 * Luu y hai slug de tuong nham: "hien-dai-" that su co dau gach ngang o cuoi,
 * va "trung-sinh" khac "trong-sinh" — cho khong phai loi go.
 */
class GenreFilter :
    Filter.Select<UriPart>(
        "Thể loại",
        arrayOf(
            UriPart("Tất cả", ""),
            UriPart("Cổ Đại", "co-dai"),
            UriPart("Hiện đại", "hien-dai-"),
            UriPart("Huyền Huyễn", "huyen-huyen"),
            UriPart("Hài Hước", "hai-huoc"),
            UriPart("Hàn Quốc", "han-quoc"),
            UriPart("Hậu Cung", "hau-cung"),
            UriPart("Hệ Thống", "he-thong"),
            UriPart("Kinh Dị", "kinh-di"),
            UriPart("Lịch Sử", "lich-su"),
            UriPart("Mạt Thế", "mat-the"),
            UriPart("Ngôn Tình", "ngon-tinh"),
            UriPart("Thanh xuân - Vườn trường", "thanh-xuan---vuon-truong"),
            UriPart("Truyện AI", "truyen-ai"),
            UriPart("Truyện Sáng Tác", "truyen-sang-tac"),
            UriPart("Trùng Sinh", "trung-sinh"),
            UriPart("Trọng sinh", "trong-sinh"),
            UriPart("Tu Tiên", "tu-tien"),
            UriPart("Xuyên Không", "xuyen-khong"),
            UriPart("Đô Thị", "do-thi"),
        ),
    ) {
    val selected: String get() = values[state].value
}

/** Tham so `status` tren URL danh sach va URL the loai. */
class StatusFilter :
    Filter.Select<UriPart>(
        "Tình trạng",
        arrayOf(
            UriPart("Tất cả", "0"),
            UriPart("Đang tiến hành", "1"),
            UriPart("Hoàn thành", "2"),
        ),
    ) {
    val selected: String get() = values[state].value
}

/** Tham so `sort` tren URL danh sach va URL the loai. Nhan lay tu chinh menu site. */
class SortFilter :
    Filter.Select<UriPart>(
        "Sắp xếp",
        arrayOf(
            UriPart("Truyện mới ra", "0"),
            UriPart("Truyện HOT", "1"),
            UriPart("Mới cập nhật", "2"),
        ),
        state = 2,
    ) {
    val selected: String get() = values[state].value
}
