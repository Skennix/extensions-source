package eu.kanade.tachiyomi.extension.vi.fapmanga

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.seconds

// Nhịp đề xuất, CHƯA ĐO trên site thật (chỉ suy đoán từ quy mô trang: trang HTML nhẹ nội dung
// text, CDN ảnh chịu tải nặng hơn nên rộng tay hơn) — CHỈNH Ở ĐÂY khi có số đo thật (log 429, hoặc
// theo dõi độ trễ qua canary GĐ sau).
private const val HTML_RATE_PERMITS = 1
private val HTML_RATE_PERIOD = 2.seconds
private const val CDN_RATE_PERMITS = 4
private val CDN_RATE_PERIOD = 1.seconds

// Còn nợ logic thật: getPopularManga, getSearchMangaList (chưa có task nào điền).
@Source
abstract class Fapmanga : KeiSource() {

    private val baseUrlHost by lazy { baseUrl.toHttpUrl().host }

    // 2 nhịp tách theo HOST (không phải theo request nói chung): trang HTML (fapmanga.com, nhẹ)
    // giãn cách hơn CDN ảnh (cdn.fapmanga.com, tải hàng chục ảnh/chương nên cần permit rộng hơn).
    // Rule cụ thể hơn (CDN_HOST) khai TRƯỚC rule chung baseUrlHost — rateLimit() áp rule ĐẦU TIÊN
    // khớp url, rule sau bị bỏ qua nếu rule trước đã khớp (xem doc keiyoushi.network.rateLimit).
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(CDN_RATE_PERMITS, CDN_RATE_PERIOD) { it.host == CDN_HOST }
        .rateLimit(HTML_RATE_PERMITS, HTML_RATE_PERIOD) { it.host == baseUrlHost }

    override suspend fun getPopularManga(page: Int): MangasPage = throw UnsupportedOperationException()

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/danh-sach?page=$page"
        // .asJsoup().html() thay vì .body.string(): nhất quán với AHottie/MissKon (dùng Jsoup của
        // response trực tiếp), trong khi vẫn giữ chữ ký String cho hàm thuần để test JVM tiêm
        // fixture qua String, không cần dựng Response giả.
        val html = client.get(url).asJsoup().html()
        return parseMangaList(html, baseUrl, "getLatestUpdates", url)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = throw UnsupportedOperationException()

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // GET trang bộ đúng MỘT lần, dùng chung cho cả details lẫn chapters theo 2 cờ — trang bộ
        // của FapManga chứa cả hai loại thông tin trên cùng một URL, không có endpoint riêng.
        val url = baseUrl + manga.url
        val html = client.get(url).asJsoup().html()
        val updatedManga = if (fetchDetails) parseMangaDetails(html, "fetchMangaUpdate", url) else manga
        val updatedChapters = if (fetchChapters) parseChapterList(html, baseUrl, "fetchMangaUpdate", url) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = baseUrl + chapter.url
        val html = client.get(url).asJsoup().html()
        return parsePageList(html, baseUrl, "getPageList", url)
    }
}
