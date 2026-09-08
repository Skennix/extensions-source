package eu.kanade.tachiyomi.extension.vi.bananacoo

import eu.kanade.tachiyomi.multisrc.madara.Madara
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.OkHttpClient
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * bananacoo.com — module MỎNG trên khuôn [Madara] có sẵn của repo.
 *
 * Vì sao mỏng: site chạy đúng theme WordPress `madara` (đo 2026-09-08 —
 * `wp-content/themes/madara`, `wp-manga`, `admin-ajax.php`, ô kết quả là
 * `div.page-item-detail.manga`), nên mọi selector mặc định của lib đã khớp. Chỉ ba thứ LỆCH so với
 * mặc định, và cả ba đều ĐO ĐƯỢC — ghi ngay dưới đây để lượt sau khỏi đoán lại.
 *
 * ── Số đo Phase 1, 2026-09-08 ────────────────────────────────────────────────────────────────
 *
 * 🔴 **`mangaSubString = "truyen"`**, không phải `manga` (mặc định). Đo: `/truyen/?m_orderby=views`
 *    trả **HTTP 200** với 29 ô `page-item-detail`; `/manga/?m_orderby=views` trả **404**.
 *
 * 🔴 **`chapterMode = MangaAjax`**, không phải `MangaPage` (mặc định). Trang bộ KHÔNG chứa
 *    `li.wp-manga-chapter` nào — chữ `wp-manga-chapter` xuất hiện 5 lần nhưng toàn trong CSS, và
 *    đó đúng là bẫy "đếm chuỗi thay vì đọc markup". Đo hai đường AJAX của Madara:
 *
 *      | đường                                          | kết quả          |
 *      | POST `<bộ>/ajax/chapters/`                      | **200, 104 chương** |
 *      | POST `/wp-admin/admin-ajax.php` (manga_get_chapters) | **400**        |
 *
 *    ⇒ `MangaAjax`. Chọn nhầm `AdminAjax` thì danh sách chương RỖNG, không lỗi gì cả.
 *
 * 🔴 **`genreDirectory = "the-loai"`**, không phải `manga-genre` (mặc định). Đo: mọi link thể loại
 *    trên trang danh sách đều là `/the-loai/<slug>/` (21 link, các slug `manga`, `manhua`,
 *    `manhwa`, `hai-huoc`).
 *
 * ⚠️ Ngày chương dạng **`dd/MM/yyyy`** (đo: `22/08/2026`, `12/08/2026`, `22/07/2026`) — mặc định
 *    của lib là `MMMM dd, yyyy` tiếng Anh, để nguyên thì mọi chương về mốc `0`, im lặng.
 *
 * Ảnh chương đi đúng khuôn Madara: `div.reading-content div.page-break img.wp-manga-chapter-img`
 * (đo chương 1: 12 ảnh, host cùng domain `wp-content/uploads/WP-manga/data/…`).
 */
@Source
abstract class BananaCoo : Madara() {

    override val mangaSubString = "truyen"

    override val chapterMode = ChapterMode.MangaAjax

    override val genreDirectory = "the-loai"

    override val chapterDateFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)

    override fun OkHttpClient.Builder.configureClient() = apply {
        rateLimit(3)
    }
}
