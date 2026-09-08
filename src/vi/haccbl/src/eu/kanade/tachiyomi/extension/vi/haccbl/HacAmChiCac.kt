package eu.kanade.tachiyomi.extension.vi.haccbl

import eu.kanade.tachiyomi.multisrc.initmanga.InitManga
import keiyoushi.annotation.Source

/**
 * haccbl.xyz ("Hắc Ám Chi Các") — module MỎNG trên khuôn [InitManga] có sẵn của repo.
 *
 * 🔴 Khuôn được chọn bằng ĐO CẤU TRÚC, không bằng tên. Site khai `wp-content/themes/init-manga`,
 * nhưng trùng tên là chưa đủ — repo đã trả giá bốn lần vì khớp theo tên. Bằng chứng thật là markup
 * khớp đúng selector mặc định của lib (đo 2026-09-08 trên `/manga/`):
 *
 *   | thứ lib trông chờ            | đo được |
 *   | `div.manga-item-grid`        | **24**  |
 *   | `div.uk-panel`               | **34**  |
 *   | `head link[rel=next]`        | có (`/manga/page/2/`) |
 *   | `div.chapter-item` (trang bộ)| **có**, link `/manga/<slug>/chapter-N/` |
 *
 * ── Ba thứ LỆCH so với mặc định của lib (lib sinh ra cho các site Thổ Nhĩ Kỳ) ─────────────────
 *
 * 🔴 **`mangaUrlDirectory = "manga"`**, mặc định là `seri`. Đo: `/manga/` trả **200** với 33 link
 *    bộ; đường `/seri/` không tồn tại ở site này.
 *
 * 🔴 **`latestUrlSlug = "moi-cap-nhat"`**, mặc định là `son-guncellemeler` (tiếng Thổ). Đo cả sáu
 *    ứng viên thay vì đoán một cái:
 *
 *      | slug                  | HTTP | ô kết quả |
 *      | `moi-cap-nhat`        | **200** | **24** |
 *      | `truyen-moi-cap-nhat` | 404 | 0 |
 *      | `cap-nhat-moi`        | 404 | 0 |
 *      | `son-guncellemeler`   | 404 | 0 |
 *      | `moi-nhat`            | 404 | 0 |
 *      | `danh-sach-a-z`       | 200 | **0** ← trang 200 mà KHÔNG có ô nào: mã 200 một mình không đủ |
 *
 *    ⚠️ Dòng cuối là lý do phải đếm ô chứ không chỉ đọc mã HTTP. Để nguyên slug Thổ thì tab "Mới
 *    cập nhật" trả rỗng, và 404 của WordPress vẫn là một trang hợp lệ — không có gì báo lỗi.
 *
 * 🔴 **`dateFormatStr` mang MÚI GIỜ**: site in `2026-09-07T15:39:26+07:00` (đọc từ thẻ `<time
 *    datetime>`), còn mặc định của lib là `yyyy-MM-dd'T'HH:mm:ss` — không có phần `+07:00` nên
 *    `SimpleDateFormat` trả về mốc `0` cho MỌI chương, im lặng.
 */
@Source
abstract class HacAmChiCac : InitManga() {

    // 🔴 CẢ BA dùng `get() =`, KHÔNG dùng `= "..."`. Đây là bẫy THỨ TỰ KHỞI TẠO của Kotlin, và nó
    // đã nổ thật khi cài bản đầu vào Suwayomi 2026-09-08:
    //
    //   java.lang.NullPointerException
    //     at java.text.SimpleDateFormat.<init>
    //     at ...InitManga.<init>
    //
    // Lớp cha có `private val fallbackDateFormatter = SimpleDateFormat(dateFormatStr, Locale.ROOT)`
    // chạy TRONG hàm dựng của CHA. Override bằng thuộc tính có backing field thì field của CON chưa
    // được gán lúc đó ⇒ cha đọc ra `null`. Dùng getter thì nó trả hằng ngay, không phụ thuộc thứ tự.
    //
    // ⚠️ Lỗi này KHÔNG lộ lúc biên dịch, KHÔNG lộ lúc build jar — jar ra bình thường, chỉ nổ lúc
    // Suwayomi dựng lớp. Cùng lý do skill bắt phải CÀI THẬT sau mỗi giai đoạn có logic mới.
    // `popularUrlSlug` của lib cũng khởi tạo từ `mangaUrlDirectory` nên cùng một bẫy.

    override val mangaUrlDirectory get() = "manga"

    override val latestUrlSlug get() = "moi-cap-nhat"

    override val dateFormatStr get() = "yyyy-MM-dd'T'HH:mm:ssXXX"
}
