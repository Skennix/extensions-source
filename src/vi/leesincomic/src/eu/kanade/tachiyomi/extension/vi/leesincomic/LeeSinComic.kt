package eu.kanade.tachiyomi.extension.vi.leesincomic

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import kotlin.time.Duration.Companion.seconds

/**
 * leesincomic.com — lớp mỏng: dựng request, đọc thân phản hồi, rồi giao cho hàm THUẦN trong
 * `LeeSinComicParsers.kt` (mọi số đo và mọi luật neo đều ghi ở đó).
 *
 * 🔴 Kế thừa **`HttpSource`**, KHÔNG `ParsedHttpSource`: KSP của repo chặn cứng
 * (`@Source class must derive from HttpSource`), đo 2026-09-08.
 *
 * 🔴 KHÔNG override `name` / `baseUrl` / `lang` — ba thứ đó do DSL `keiyoushi { source { … } }`
 * trong `build.gradle.kts` cấp (`lang is owned by the DSL`). Khai hai chỗ thì có ngày lệch, và
 * bản trong mã sẽ im lặng thắng.
 *
 * Đường vào đã đo (Phase 1, BACKLOG (146) của repo `manga-crawler`), 48 mục/trang, `?page=N`:
 *   POPULAR `/top-thang.html` · LATEST `/truyen-moi-cap-nhat.html` · SEARCH `/tim-kiem.html?key=`
 *
 * ⚠️ Ảnh chết ở gốc là ca CÓ THẬT (đo Phase 1: 1/6 bộ trả 404 ở mọi tổ hợp header, 403 khi bỏ UA).
 *    `pageListParse` trả được danh sách mà tải vẫn 404 — đừng đọc 404 thành "parser sai".
 */
@Source
abstract class LeeSinComic : HttpSource() {

    override val supportsLatest = true

    /**
     * Nhịp hai tầng theo skill: HTML chậm, ảnh nhanh hơn. Người đọc thật mở MỘT trang HTML rồi
     * trình duyệt kéo hàng chục ảnh — bóp ảnh chậm bằng nhịp HTML là chờ vô ích mà chẳng giống ai.
     *
     * Luật riêng phải đứng TRƯỚC luật chung (luật đầu tiên khớp là luật được áp).
     * Neo theo ĐƯỜNG DẪN `uploads/minh_hoa/` chứ không theo host: host ảnh có ba cái và còn đổi.
     */
    override val client = network.client.newBuilder()
        .rateLimit(permits = 4, period = 1.seconds) { laAnhTruyen(it.toString()) }
        .rateLimit(permits = 1, period = 2.seconds)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ─────────────────────────────── Danh sách ────────────────────────────────

    override fun popularMangaRequest(page: Int): Request = GET(trangDanhSach("$baseUrl/top-thang.html", page), headers)

    override fun popularMangaParse(response: Response): MangasPage = parseMangaList(response.body.string(), "popularMangaParse", response.request.url.toString())

    override fun latestUpdatesRequest(page: Int): Request = GET(trangDanhSach("$baseUrl/truyen-moi-cap-nhat.html", page), headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangaList(response.body.string(), "latestUpdatesParse", response.request.url.toString())

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isBlank()) return popularMangaRequest(page)

        val url = "$baseUrl/tim-kiem.html".toHttpUrl().newBuilder()
            .addQueryParameter("key", query)
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    /**
     * Ngoại lệ DUY NHẤT của luật "rỗng ⇒ ném lỗi": tìm kiếm CÓ TỪ KHOÁ mà không ra gì là kết quả
     * HỢP LỆ (đo `key=zzzqqq`: 0 mục trong `div.li_truyen`). Tìm kiếm KHÔNG từ khoá đi đường
     * `popularMangaRequest` nên vẫn phải ném khi rỗng — đó là trang danh sách, rỗng là bất thường.
     */
    override fun searchMangaParse(response: Response): MangasPage {
        val url = response.request.url
        val coTuKhoa = !url.queryParameter("key").isNullOrBlank()
        return parseMangaList(response.body.string(), "searchMangaParse", url.toString(), allowEmpty = coTuKhoa)
    }

    private fun trangDanhSach(goc: String, page: Int): HttpUrl = goc.toHttpUrl().newBuilder().addQueryParameter("page", page.toString()).build()

    // ──────────────────────────────── Trang bộ ────────────────────────────────

    override fun mangaDetailsParse(response: Response): SManga = parseMangaDetails(response.body.string(), "mangaDetailsParse", response.request.url.toString())

    override fun chapterListParse(response: Response): List<SChapter> = parseChapterList(response.body.string(), "chapterListParse", response.request.url.toString())

    override fun pageListParse(response: Response): List<Page> = parsePageList(response.body.string(), "pageListParse", response.request.url.toString())

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Không dùng: pageListParse đã trả sẵn imageUrl.")
}
