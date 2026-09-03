package eu.kanade.tachiyomi.extension.vi.comicvn

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
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/**
 * comicvn19.net - Laravel + MongoDB tu viet (khong phai NetTruyen/Madara nen khong dung duoc themePkg).
 *
 * Dinh dang URL:
 *   truyen  : /{slug}-{objectid}.html
 *   chuong  : /{slug}/chapter-{n}-{objectid}.html
 * `objectid` la ObjectId cua Mongo - 24 ky tu hex.
 */
@Source
abstract class ComicVN : KeiSource() {

    private val siteHost get() = baseUrl.toHttpUrl().host

    override fun OkHttpClient.Builder.configureClient() = apply {
        rateLimit(2, 1.seconds) { it.host.isSiteHost() }

        // BAY 2: CDN anh doi DONG THOI Referer va User-Agent; thieu mot trong hai la 403,
        // va Referer tro sang ten mien khac cung 403 (do 2026-09-03: UA don 403, Referer don 403,
        // UA + Referer google.com 403, UA + Referer chinh site 200).
        //
        // `headersBuilder()` cua KeiSource da dat san Referer cho moi request di qua `headers`,
        // va KeiSource `check(...)` bat buoc UserAgentInterceptor phai co trong client goc
        // (no chay TRUOC interceptor nay nen UA luon duoc dien) - nen day chi la lop chan cuoi
        // cho request anh do host app tu dung ma khong kem headers cua nguon.
        //
        // Host CDN xoay `cdn / cdn2 / cdn4 / cdn5 / static` nen khop theo hau to cua ten mien
        // dang dung, KHONG hang mot host cu the.
        addInterceptor { chain ->
            val request = chain.request()
            if (!request.url.host.isSiteHost() || request.header("Referer") != null) {
                chain.proceed(request)
            } else {
                chain.proceed(
                    request.newBuilder()
                        .header("Referer", "$baseUrl/")
                        .build(),
                )
            }
        }
    }

    /** Dung ten mien dang chon hoac bat ky ten mien con nao cua no (cdn5.…, static.…). */
    private fun String.isSiteHost(): Boolean {
        val host = siteHost
        return this == host || endsWith(".$host")
    }

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage = parseMangaPage(client.get(browseUrl(page, sort = "xem-nhieu")))

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseMangaPage(client.get(browseUrl(page, sort = "moi-nhat")))

    // =============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        // Tim theo ten di duong /tim-kiem; loc di duong /danh-muc/tat-ca-truyen.
        // Hai duong khong dung chung tham so nen khong gop lam mot.
        val url = if (query.isNotBlank()) {
            "$baseUrl/tim-kiem".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("page", page.toString())
                .build()
        } else {
            "$baseUrl/danh-muc/tat-ca-truyen".toHttpUrl().newBuilder().apply {
                filters.forEach { filter ->
                    when (filter) {
                        is StatusFilter -> filter.values[filter.state].id
                            .takeIf { it.isNotEmpty() }
                            ?.let { addQueryParameter("status", it) }

                        is SortFilter -> filter.values[filter.state].id
                            .takeIf { it.isNotEmpty() }
                            ?.let { addQueryParameter("sort", it) }

                        // Form cua site chi nhan MOT the loai (`<select name="cate">`),
                        // nen lay cai duoc tick dau tien thay vi noi chuoi - noi chuoi
                        // se ra 0 ket qua ma khong bao loi.
                        is GenreFilter -> filter.state.firstOrNull { it.state }
                            ?.let { addQueryParameter("cate", it.id) }

                        else -> {}
                    }
                }
                addQueryParameter("page", page.toString())
            }.build()
        }

        return parseMangaPage(client.get(url))
    }

    private fun browseUrl(page: Int, sort: String): HttpUrl = "$baseUrl/danh-muc/tat-ca-truyen".toHttpUrl().newBuilder()
        .addQueryParameter("sort", sort)
        .addQueryParameter("page", page.toString())
        .build()

    private fun parseMangaPage(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(".listComic ul.list > li").map { element ->
            SManga.create().apply {
                val anchor = element.selectFirst(".cover > a")!!
                setUrlWithoutDomain(anchor.attr("abs:href"))
                title = anchor.attr("title")
                    .ifBlank { element.selectFirst(".detail h3")?.text().orEmpty() }
                thumbnail_url = element.selectFirst(".cover img")?.thumbnail()
            }
        }

        // BAY 4: het trang KHONG phai 404 - `?page=500` van tra 200 kem mot trang day du,
        // chi thieu danh sach truyen. Nen dieu kien dung phai la "trang nay co truyen khong",
        // khong the dua vao ma HTTP hay rieng su ton tai cua nut ">".
        val hasNextPage = mangas.isNotEmpty() &&
            document.selectFirst(".paging li.next > a[href]") != null

        return MangasPage(mangas, hasNextPage)
    }

    /**
     * BAY (bia): anh bia nam o `data-src` (lazysizes), con `src` la anh placeholder
     * `images/default.png` dung chung. Doc `src` se ra 36 anh giong het nhau ca trang.
     */
    private fun Element.thumbnail(): String? = absUrl("data-src").ifBlank { null } ?: absUrl("src").ifBlank { null }

    // =========================== Manga Details ============================

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (!url.host.isSiteHost()) return null
        val path = "/" + url.pathSegments.joinToString("/")
        if (mangaIdRegex.find(path) == null) return null

        return parseMangaDetails(client.get("$baseUrl$path").asJsoup()).apply {
            setUrlWithoutDomain("$baseUrl$path")
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()

        return SMangaUpdate(
            parseMangaDetails(document),
            layDanhSachChuong(manga),
        )
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        val detail = document.selectFirst(".detailComic .preface .detail")

        title = detail?.selectFirst("h1")?.text().orEmpty()
        description = detail?.selectFirst(".shortDetail")?.wholeText()?.trim()
        genre = document.select("ul.list-cate a").joinToString { it.text() }
        thumbnail_url = document.selectFirst(".detailComic .preface .cover img")?.thumbnail()

        // Trang chi tiet KHONG co truong tac gia va KHONG co truong tinh trang.
        // Da doi chieu 3 bo (dao-kiem-tieu, tuyet-dai-song-kieu, pham-nhan-tu-tien): khoi
        // `ul.aboutThisComic` chi co "Nhom dich" va "Luot xem". "Nhom dich" la nhom dich,
        // KHONG phai tac gia - gan no vao `author` la ghi sai du lieu, nen de trong.
        // Tinh trang chi ton tai nhu BO LOC tim kiem, khong hien tren trang truyen.
        status = SManga.UNKNOWN
    }

    // ============================== Chapters ==============================

    @Serializable
    private class ChapterListDto(
        val status: Boolean = false,
        val data: ChapterListData? = null,
    )

    @Serializable
    private class ChapterListData(
        val chaptersHtml: String? = null,
    )

    /**
     * BAY 1: trang chi tiet chi in DUNG HAI link chuong (nut "chuong dau" / "chuong cuoi").
     * Danh sach that nap bang AJAX, va endpoint do BAT BUOC co `X-Requested-With`.
     *
     * Thieu header KHONG ra loi - no tra `200 OK` kem 81 byte `{"status":false,...}`, nen
     * mot parser ngay tho se doc thanh "bo nay 0 chuong" va hong hoan toan im lang.
     * Vi vay o day kiem tra tuong minh `status` va `chaptersHtml`, va NEM LOI thay vi
     * tra ve danh sach rong.
     */
    // Ten KHONG duoc la `fetchChapterList`: KeiSource da co
    // `fetchChapterList(SManga): Observable<List<SChapter>>` (khong suspend), nen trung ten +
    // trung kieu tham so lam Kotlin coi day la mot phep nap chong hong. Da tra gia mot luot
    // build 2026-09-04. `manhuavn` thoat vi tham so cua no la Document, khac kieu.
    private suspend fun layDanhSachChuong(manga: SManga): List<SChapter> {
        val mangaId = mangaIdRegex.find(manga.url)?.groupValues?.get(1)
            ?: throw Exception("Khong doc duoc ma truyen tu duong dan: ${manga.url}")

        val url = "$baseUrl/thong-tin-ca-nhan".toHttpUrl().newBuilder()
            .addQueryParameter("manga_id", mangaId)
            .build()

        val ajaxHeaders = headersBuilder()
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", getMangaUrl(manga))
            .build()

        val dto = client.get(url, ajaxHeaders, CacheControl.FORCE_NETWORK).parseAs<ChapterListDto>()

        val chaptersHtml = dto.data?.chaptersHtml
        if (!dto.status || chaptersHtml.isNullOrBlank()) {
            throw Exception("Khong lay duoc danh sach chuong - site tu choi yeu cau AJAX")
        }

        // `chaptersHtml` la chuoi HTML long trong JSON => phai phan tich luot hai.
        return Jsoup.parseBodyFragment(chaptersHtml, baseUrl)
            .select("li:has(a[href])")
            .map(::parseChapter)
    }

    private fun parseChapter(element: Element): SChapter = SChapter.create().apply {
        val anchor = element.selectFirst("a[href]")!!
        val href = anchor.attr("abs:href")
        setUrlWithoutDomain(href)

        val label = element.selectFirst(".titleComic")?.ownText()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: chapterNumberRegex.find(href)?.let { "Chapter ${it.groupValues[1]}" }
            ?: anchor.text().trim()

        // Chuong tra phi: trong danh sach no co khoi `.vip-chapter` (o khoa + gia bang xu).
        // Do 2026-09-03 tren "Dao Kiem Tieu": 278/295 chuong bi khoa. Trang cua mot chuong
        // bi khoa van tra 200 nhung KHONG he chua the <img> nao - xem `getPageList`.
        // Danh dau o ten de nguoi dung biet truoc thay vi bam vao roi gap chuong rong.
        val locked = element.selectFirst(".vip-chapter") != null
        name = if (locked) "🔒 $label" else label

        date_upload = element.selectFirst(".views")?.text()
            ?.let { dateRegex.find(it)?.value }
            ?.let(::parseDate)
            ?: 0L
    }

    private fun parseDate(date: String): Long = runCatching {
        LocalDateTime.parse(date, dateFormat)
            .atZone(ZoneId.of("Asia/Ho_Chi_Minh"))
            .toInstant()
            .toEpochMilli()
    }.getOrDefault(0L)

    // =============================== Pages ================================

    /**
     * Anh trang nam san trong HTML, khong bi lam roi.
     *
     * BAY 3: the anh dung NHAY DON - `<img src='https://cdn…' …/>`. Moi phep loc bang
     * regex kieu `src="…"` deu tra ve 0 anh va dan toi ket luan sai la "anh nap bang JS".
     * Jsoup xu ly dung ca hai kieu nhay nen o day dung Jsoup, khong dung regex.
     */
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter), CacheControl.FORCE_NETWORK).asJsoup()

        // Chuong TRA PHI tra ve mot khuon trang KHAC HAN (html lang="en", khong he co
        // `#lightgallery2` / `.comicDetail2`, chi co moi giao dien "Xu / Dang nhap").
        // Do 2026-09-03 tren "Dao Kiem Tieu": 278/295 chuong bi khoa, va trang chuong 295
        // tra 200 kem 238 KB nhung KHONG mot the anh truyen nao.
        // => Phai bao loi ro rang, tuyet doi khong tra ve danh sach rong (app se hieu nham
        //    thanh "chuong 0 trang" va hong im lang).
        val container = document.selectFirst("#lightgallery2")
            ?: document.selectFirst(".comicDetail2")
            ?: throw Exception(
                "Chuong nay can tai khoan tra phi cua site (mo khoa bang Xu) nen khong co anh",
            )

        // Chi lay anh truyen: duong dan anh trang luon di qua `/chapter/` tren CDN,
        // nen loc nay gat duoc banner/loading.gif nam chung khoi.
        val images = container.select("img[src*='/chapter/']")
        if (images.isEmpty()) {
            throw Exception(
                "Chuong nay can tai khoan tra phi cua site (mo khoa bang Xu) nen khong co anh",
            )
        }

        // 🔴 Trang cua MOT chuong co the chua luon anh cua chuong KE TIEP, dan lien nhau,
        // khong he co the boc nao ngan cach (do 2026-09-03: trang "chapter-1" cua
        // "Dao Kiem Tieu" co 15 anh chuong 1 + 16 anh chuong 2 = 31 the <img>).
        // Lay het se nhan doi moi chuong ma khong co dau hieu gi bao loi.
        //
        // ⚠️ KHONG cat theo thuoc tinh `alt`: da do, ca 31 anh deu mang CUNG mot alt
        // "Dao Kiem Tieu - Chapter 1", ke ca 16 anh thuoc chuong 2 => alt vo dung o day.
        // Thu phan biet duoc la TEN TEP tren CDN:
        //   {mangaObjectId}-chapter-{n}-{thu tu}.jpg
        // Bo phan "-{thu tu}.{duoi}" thi con khoa dinh danh cua chuong; giu doan dau
        // co cung khoa voi anh thu nhat la ra dung pham vi mot chuong. Cach nay khong
        // phu thuoc vao cach dat ten chuong (co bo dat "chapter-800-het", "chapter-1279-1280-het").
        val firstKey = images.first()!!.imageKey()
        val pageImages = images.takeWhile { it.imageKey() == firstKey }

        return pageImages.mapIndexed { index, image ->
            Page(index, imageUrl = image.attr("abs:src"))
        }
    }

    /**
     * Khoa dinh danh chuong suy ra tu duong dan anh: bo duoi "-{thu tu}.{duoi tep}".
     * `…/{oid}-chapter-2-0.jpg` -> `…/{oid}-chapter-2`
     */
    private fun Element.imageKey(): String {
        val src = attr("abs:src")
        return imageIndexRegex.find(src)?.groupValues?.get(1) ?: src
    }

    // ============================== Filters ===============================

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("Bo loc khong dung chung voi tim kiem theo ten"),
        StatusFilter(),
        SortFilter(),
        GenreFilter(getGenreList()),
    )

    companion object {
        /** ObjectId cua Mongo o cuoi duong dan truyen: `/{slug}-{objectid}.html`. */
        private val mangaIdRegex = Regex("""-([0-9a-f]{24})\.html""")

        // Ten chuong khong phai luc nao cung la mot so: da gap "chapter-800-het",
        // "chapter-1279-1280-het". Bat tron doan giua "/chapter-" va ma ObjectId.
        private val chapterNumberRegex = Regex("""/chapter-(.+)-[0-9a-f]{24}\.html""")

        /** Duoi "-{thu tu}.{duoi tep}" cua duong dan anh trang. */
        private val imageIndexRegex = Regex("""^(.*)-\d+\.[A-Za-z0-9]+$""")

        /** Vi du: `03-09-2026 16:16`. */
        private val dateRegex = Regex("""\d{2}-\d{2}-\d{4} \d{2}:\d{2}""")

        private val dateFormat = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm", Locale.ROOT)
    }
}
