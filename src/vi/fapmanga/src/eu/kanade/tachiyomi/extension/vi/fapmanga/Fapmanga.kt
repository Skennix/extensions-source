package eu.kanade.tachiyomi.extension.vi.fapmanga

import eu.kanade.tachiyomi.source.model.Filter
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
import kotlinx.serialization.json.JsonElement
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

// Danh sách thể loại TĨNH, trích tay 2026-08-17 từ nav trong fixture test/fixtures/danh-sach.html
// (23 link unique "/the-loai/<slug>"). Site không phơi endpoint nào để tự dựng lại danh sách này ⇒ CŨ DẦN
// theo thời gian, và canary GĐ6 (T9) KHÔNG canh được việc site thêm/bớt/đổi thể loại — chỉ canh
// được URL mẫu bộ/chương/ảnh. Ghi vào BACKLOG (đã ghi khi làm T8).
private val GENRES = arrayOf(
    "-- Tất cả --" to "",
    "3D Hentai" to "3d-hentai",
    "Ahegao" to "ahegao",
    "BDSM" to "bdsm",
    "Big Ass" to "big-ass",
    "Big Boobs" to "big-boobs",
    "Blowjobs" to "blowjobs",
    "Cosplay" to "cosplay",
    "Doujinshi" to "doujinshi",
    "Ecchi" to "ecchi",
    "Elf" to "elf",
    "Fantasy" to "fantasy",
    "Full Color" to "full-color",
    "Góc Nhìn Nữ" to "goc-nhin-nu",
    "Harem" to "harem",
    "Hentai 18+" to "hentai-18",
    "Isekai" to "isekai",
    "Manhwa" to "manhwa",
    "MILF" to "milf",
    "Mind Break" to "mind-break",
    "NTR" to "ntr",
    "Romance" to "romance",
    "Schoolgirl" to "schoolgirl",
    "Vanilla" to "vanilla",
)

// Filter đơn-chọn (không phải checkbox nhiều-chọn như GenreFilter của vihentai/1.6): FapManga ánh
// xạ MỘT thể loại sang MỘT đường dẫn "/the-loai/<slug>", không có API kết hợp nhiều thể loại, nên
// "chọn 1 trong N" (Filter.Select, theo mẫu UriPartFilter của src/vi/vihentai) đúng ngữ nghĩa hơn.
class GenreFilter : Filter.Select<String>("Thể loại", GENRES.map { it.first }.toTypedArray()) {
    fun toUriPart(): String = GENRES[state].second
}

@Source
abstract class Fapmanga : KeiSource() {

    private val baseUrlHost by lazy { baseUrl.toHttpUrl().host }

    // 2 nhịp tách theo HOST (không phải theo request nói chung): trang HTML (fapmanga.com, nhẹ)
    // giãn cách hơn CDN ảnh (cdn.fapmanga.com, tải hàng chục ảnh/chương nên cần permit rộng hơn).
    // Rule cụ thể hơn (CDN_HOST) khai TRƯỚC rule chung baseUrlHost — rateLimit() áp rule ĐẦU TIÊN
    // khớp url, rule sau bị bỏ qua nếu rule trước đã khớp (xem doc keiyoushi.network.rateLimit).
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(CDN_RATE_PERMITS, CDN_RATE_PERIOD) { it.host == CDN_HOST }
        .rateLimit(HTML_RATE_PERMITS, HTML_RATE_PERIOD) { it.host == baseUrlHost }

    override suspend fun getPopularManga(page: Int): MangasPage {
        // Đo #5 (RESEARCH.md, 2026-08-17): "?rank=week&page=N" chạy, phát link tới page=75. Gọi
        // lại parseMangaList của T4 nguyên xi — trang xếp hạng dùng CÙNG khối .card-grid với trang
        // danh sách, chỉ khác widget/sidebar dùng chung xung quanh (đã khoá test ở 7.2/T4).
        val url = "$baseUrl/?rank=week&page=$page"
        val html = client.get(url).asJsoup().html()
        return parseMangaList(html, baseUrl, "getPopularManga", url)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/danh-sach?page=$page"
        // .asJsoup().html() thay vì .body.string(): nhất quán với AHottie/MissKon (dùng Jsoup của
        // response trực tiếp), trong khi vẫn giữ chữ ký String cho hàm thuần để test JVM tiêm
        // fixture qua String, không cần dựng Response giả.
        val html = client.get(url).asJsoup().html()
        return parseMangaList(html, baseUrl, "getLatestUpdates", url)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        // Luật ưu tiên + dựng URL nằm ở hàm THUẦN buildSearchRequest (FapmangaParsers.kt) để test
        // JVM kiểm được không cần gọi mạng — xem doc ở đó.
        val genreSlug = filters.filterIsInstance<GenreFilter>().firstOrNull()?.toUriPart().orEmpty()
        val request = buildSearchRequest(baseUrl, page, query, genreSlug)
        val url = request.url ?: return getLatestUpdates(page)

        val html = client.get(url).asJsoup().html()
        val result = parseMangaList(html, baseUrl, "getSearchMangaList", url, allowEmpty = request.allowEmpty)
        return if (request.forceNoNextPage) result.copy(hasNextPage = false) else result
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(GenreFilter())

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
