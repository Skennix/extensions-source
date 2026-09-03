package eu.kanade.tachiyomi.extension.vi.comicvn

import eu.kanade.tachiyomi.source.model.Filter

/**
 * Lua chon cua form tim kiem nang cao o /danh-muc/tat-ca-truyen.
 *
 * Sinh thang tu HTML cua site (cac the <select name="cate|status|sort">) do ngay
 * 2026-09-03, khong go tay - de slug va con so khong lech theo thoi gian.
 */
class Option(val name: String, val id: String) {
    override fun toString() = name
}

class Genre(name: String, val id: String) : Filter.CheckBox(name)

class GenreFilter(genres: List<Genre>) : Filter.Group<Genre>("The loai", genres)

class StatusFilter :
    Filter.Select<Option>(
        "Tinh trang",
        arrayOf(
            Option("Tất cả", ""),
            Option("Đang tiến hành", "2"),
            Option("Đã hoàn tất", "1"),
        ),
    )

class SortFilter :
    Filter.Select<Option>(
        "Sap xep",
        arrayOf(
            Option("Tất cả", ""),
            Option("Xem nhiều", "xem-nhieu"),
            Option("Mới nhất", "moi-nhat"),
            Option("Cũ nhất", "cu-nhat"),
        ),
    )

/**
 * 82 the loai, lay tu <select name="cate">.
 * Muc "Tat ca" (value rong) bi bo vi dang checkbox khong can no.
 */
fun getGenreList(): List<Genre> = listOf(
    Genre("Action", "action"),
    Genre("Fantasy", "fantasy"),
    Genre("Manhua", "manhua"),
    Genre("Martial Arts", "martial-arts"),
    Genre("Truyện Màu", "truyen-mau"),
    Genre("Chuyển Sinh", "chuyen-sinh"),
    Genre("Mystery", "mystery"),
    Genre("Ngôn Tình", "ngon-tinh"),
    Genre("Manhwa", "manhwa"),
    Genre("Adventure", "adventure"),
    Genre("Cổ Đại", "co-ai"),
    Genre("Comedy", "comedy"),
    Genre("Drama", "drama"),
    Genre("Historical", "historical"),
    Genre("Xuyên Không", "xuyen-khong"),
    Genre("Romance", "romance"),
    Genre("School Life", "school-life"),
    Genre("Slice of Life", "slice-of-life"),
    Genre("Supernatural", "supernatural"),
    Genre("Comic", "comic"),
    Genre("Việt Nam", "viet-nam"),
    Genre("Shounen", "shounen"),
    Genre("Webtoon", "webtoon"),
    Genre("Horror", "horror"),
    Genre("Psychological", "psychological"),
    Genre("Seinen", "seinen"),
    Genre("Manga", "manga"),
    Genre("Sci-fi", "sci-fi"),
    Genre("Tragedy", "tragedy"),
    Genre("Sports", "sports"),
    Genre("Anime", "anime"),
    Genre("Thiếu Nhi", "thieu-nhi"),
    Genre("Mecha", "mecha"),
    Genre("Trinh Thám", "trinh-tham"),
    Genre("One shot", "one-shot"),
    Genre("Tạp chí truyện tranh", "tap-chi-truyen-tranh"),
    Genre("Doujinshi", "doujinshi"),
    Genre("Live action", "live-action"),
    Genre("Cooking", "cooking"),
    Genre("Truyện scan", "truyen-scan"),
    Genre("Beeng.net", "beengnet"),
    Genre("Truyện Nhật (Manga)", "truyen-nhat-manga"),
    Genre("Cổ Đại", "co-dai"),
    Genre("Manhwa", "truyen-han-manhwa"),
    Genre("Detective", "detective"),
    Genre("Trọng Sinh", "trong-sinh"),
    Genre("Chuyển sinh", "isekai"),
    Genre("Huyền Huyễn", "huyen-huyen"),
    Genre("Game", "game"),
    Genre("Beeng.net", "beeng-net"),
    Genre("Chuyển sinh", "isekaidi-gioitrong-sinh"),
    Genre("Magic", "magic"),
    Genre("Psycho", "psycho"),
    Genre("Truyện màu", "full-mau"),
    Genre("Demons", "demons"),
    Genre("Manhua", "truyen-tau-manhua"),
    Genre("Hệ Thống", "he-thong"),
    Genre("Tu tiên", "tu-tien"),
    Genre("Tu ch&amp;#226;n - tu ti&amp;#234;n", "tu-ch226n-tu-ti234n"),
    Genre("Võ lâm", "vo-lam"),
    Genre("Truyện Trung", "truyen-trung"),
    Genre("Hài Hước", "hai-huoc"),
    Genre("Hành Động", "hanh-dong"),
    Genre("Học Đường", "hoc-duong"),
    Genre("Phiêu Lưu", "phieu-luu"),
    Genre("Siêu Nhiên", "sieu-nhien"),
    Genre("Kịch Tính", "kich-tinh"),
    Genre("Kinh Dị", "kinh-di"),
    Genre("Tâm Lý", "tam-ly"),
    Genre("Khoa Học Viễn Tưởng", "khoa-hoc-vien-tuong"),
    Genre("Bí Ẩn", "bi-an"),
    Genre("Võ Thuật", "vo-thuat"),
    Genre("Bi Kịch", "bi-kich"),
    Genre("Thể Thao", "the-thao"),
    Genre("Trưởng Thành", "truong-thanh"),
    Genre("Đời Thường", "doi-thuong"),
    Genre("Lịch Sử", "lich-su"),
    Genre("Lãng Mạn", "lang-man"),
    Genre("Nấu Nướng", "nau-nuong"),
    Genre("Truyện Âu Mỹ", "truyen-au-my"),
    Genre("Đấu Trí", "dau-tri"),
    Genre("Tận Thế", "tan-the"),
)
