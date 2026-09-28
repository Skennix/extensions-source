package eu.kanade.tachiyomi.extension.vi.damconuong

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getLocalStorage
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * BACKLOG (153), 2026-09-28 — site đã viết lại HOÀN TOÀN trên một nền tảng đọc truyện mới
 * (Next.js/React), không còn là site scrape-HTML cũ. Đo trực tiếp qua VPN (`curl
 * --socks5-hostname`, không phải đoán): trang HTML không còn mang dữ liệu — mọi listing/chi
 * tiết/chương đọc từ một API JSON riêng ở [API_URL], và trang ĐỌC CHƯƠNG hoàn toàn không có ảnh
 * nào trong HTML thô (nạp qua JS sau khi trang hiện), phải gọi thẳng API.
 *
 * 🔴 Endpoint ảnh trang (`/mangas/{slug}/chapters/{slug}/pages`) bắt buộc phải ĐĂNG NHẬP — gọi
 * không có `Authorization: Bearer <token>` trả `403 {"code":"invalid_token"}`, và KHÔNG tìm thấy
 * đường khách/ẩn danh nào (không có endpoint `/auth/guest`, không có ảnh dự phòng trong HTML cho
 * khách như một số site cùng họ — xem ViTruyen.kt cùng repo, site đó CÓ đường khách). Xử lý bằng
 * đúng khuôn ViTruyen đã dùng: đọc token đã đăng nhập từ localStorage của WebView (user tự đăng
 * nhập MỘT LẦN qua "Mở trong WebView" của app đọc truyện, y hệt đăng nhập trên trình duyệt thường)
 * — không tự chế/đoán cách sinh token.
 *
 * ⚠️ Chưa có token thật để xác minh HÌNH DẠNG response thành công của `/pages` (chỉ đo được ca
 * 403) — [extractPageUrls] cố tình thử NHIỀU hình dạng hợp lý (mảng chuỗi thẳng / mảng object có
 * field `url`/`image_url`/`src` / bọc thêm trong `images`/`pages`) thay vì giả định MỘT hình dạng
 * duy nhất. Ai đăng nhập thành công mà vẫn không đọc được ảnh: log dòng "VN verify"-style qua
 * exception ném ra, xem đúng response thật rồi chỉnh lại hàm này.
 */
@Source
abstract class DamCoNuong : KeiSource() {

    private val apiUrl = API_URL

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addInterceptor(authInterceptor())
        .rateLimit(5)

    // ================================ Auth ================================
    //
    // Cùng khuôn ViTruyen.kt (cùng repo) — đọc token đăng nhập từ localStorage của WebView, KHÔNG
    // tự sinh/đoán token.

    private var cachedAuthToken: String? = null
    private var authChecked = false

    private suspend fun loadAuthToken() {
        if (authChecked) return
        authChecked = true
        cachedAuthToken = readAuthToken()
    }

    private suspend fun refreshAuthToken(): Boolean {
        val staleToken = cachedAuthToken
        cachedAuthToken = readAuthToken()
        return cachedAuthToken != null && cachedAuthToken != staleToken
    }

    /**
     * 🔴 Site lưu phiên đăng nhập bằng zustand `persist` dưới khoá `"auth-storage"` — GIÁ TRỊ là
     * MỘT chuỗi JSON `{"state":{"token":"...",...},"version":N}` (khuôn chuẩn của zustand persist,
     * đọc được từ mã JS đã tải: `persist(..., {name:"auth-storage", storage: ...localStorage})`),
     * KHÁC ViTruyen (nơi khoá `"auth_token"` giữ THẲNG chuỗi token, không bọc JSON) — không dùng
     * lại y nguyên logic của ViTruyen được, phải tự bóc lớp `state.token`.
     */
    private suspend fun readAuthToken(): String? = getLocalStorage(baseUrl, "auth-storage")
        ?.let { raw ->
            try {
                Json.parseToJsonElement(raw).jsonObject["state"]
                    ?.jsonObject?.get("token")
                    ?.jsonPrimitive?.contentOrNull
            } catch (e: Exception) {
                null
            }
        }
        ?.takeIf { it.isNotBlank() }

    private fun authInterceptor() = Interceptor { chain ->
        val original = chain.request()
        val request = original.newBuilder().apply {
            if (original.url.host == apiUrl.toHttpUrl().host) {
                cachedAuthToken?.let { header("Authorization", "Bearer $it") }
            }
        }.build()
        chain.proceed(request)
    }

    // ============================== Popular ================================

    override suspend fun getPopularManga(page: Int): MangasPage = getMangasPage(
        "$apiUrl/mangas/hot".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .build(),
    )

    // =============================== Latest =================================

    override suspend fun getLatestUpdates(page: Int): MangasPage = getMangasPage(
        "$apiUrl/mangas/recent".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .build(),
    )

    // =============================== Search =================================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotBlank()) {
            return getMangasPage(
                "$apiUrl/mangas/search".toHttpUrl().newBuilder()
                    .addQueryParameter("q", query)
                    .addQueryParameter("page", page.toString())
                    .build(),
                responseKey = "data",
            )
        }

        val status = filters.firstInstanceOrNull<StatusFilter>()?.toUriPart()
        return getMangasPage(
            "$apiUrl/mangas".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())
                .apply { status?.let { addQueryParameter("filter[status]", it) } }
                .build(),
        )
    }

    private suspend fun getMangasPage(url: HttpUrl, responseKey: String = "data"): MangasPage {
        val response = client.get(url).parseAs<ApiListResponse<MangaDto>>()

        return MangasPage(
            mangas = response.data.map { it.toSManga() },
            hasNextPage = response.meta?.pagination
                ?.let { it.currentPage < it.lastPage }
                ?: false,
        )
    }

    // =============================== Details ================================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/truyen/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/truyen/${chapter.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        if (url.pathSegments.getOrNull(0) != "truyen") return null

        val slug = url.pathSegments.getOrNull(1) ?: return null
        return getMangaDetails(slug).toSManga().apply { this.url = slug }
    }

    private suspend fun getMangaDetails(slug: String): MangaDto = client.get("$apiUrl/mangas/$slug")
        .parseAs<ApiSingleResponse<MangaDto>>()
        .data

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slug = manga.url

        return SMangaUpdate(
            manga = if (fetchDetails) getMangaDetails(slug).toSManga().apply { url = slug } else manga,
            chapters = if (fetchChapters) getAllChapters(slug).map { it.toSChapter(slug) } else emptyList(),
        )
    }

    // ============================== Chapters ================================

    /**
     * 🔴 Endpoint chương CÓ PHÂN TRANG (đo được: 124 chương / 50 mỗi trang = 3 trang) — gọi đúng
     * một lượt như bản cũ sẽ MẤT 74 chương cuối. Phải gom hết các trang.
     */
    private suspend fun getAllChapters(slug: String): List<ChapterDto> {
        val chapters = mutableListOf<ChapterDto>()
        var page = 1

        while (true) {
            val response = client.get(
                "$apiUrl/mangas/$slug/chapters".toHttpUrl().newBuilder()
                    .addQueryParameter("page", page.toString())
                    .build(),
            ).parseAs<ApiListResponse<ChapterDto>>()

            chapters += response.data

            val pagination = response.meta?.pagination ?: break
            if (pagination.currentPage >= pagination.lastPage) break
            page++
        }

        return chapters
    }

    // =============================== Pages ==================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        loadAuthToken()

        val (mangaSlug, chapterSlug) = chapter.url.split("/", limit = 2)
            .let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }

        val imageUrls = fetchPageUrls(mangaSlug, chapterSlug)

        if (imageUrls.isEmpty()) {
            throw Exception(LOCKED_CHAPTER_MESSAGE)
        }

        return imageUrls.mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    private suspend fun fetchPageUrls(mangaSlug: String, chapterSlug: String): List<String> {
        if (cachedAuthToken == null) return emptyList()

        fetchPageUrlsFromApi(mangaSlug, chapterSlug)?.let { if (it.isNotEmpty()) return it }

        if (!refreshAuthToken()) return emptyList()

        return fetchPageUrlsFromApi(mangaSlug, chapterSlug) ?: emptyList()
    }

    private suspend fun fetchPageUrlsFromApi(mangaSlug: String, chapterSlug: String): List<String>? = try {
        client.get("$apiUrl/mangas/$mangaSlug/chapters/$chapterSlug/pages")
            .parseAs<JsonElement>()
            .let(::extractPageUrls)
    } catch (e: Exception) {
        null
    }

    /** Xem docstring lớp — hình dạng response THẬT của endpoint này chưa được xác minh. */
    private fun extractPageUrls(root: JsonElement): List<String> {
        val data = (root as? JsonObject)?.get("data") ?: return emptyList()

        val array = when (data) {
            is JsonArray -> data
            is JsonObject -> (data["images"] as? JsonArray) ?: (data["pages"] as? JsonArray) ?: return emptyList()
            else -> return emptyList()
        }

        return array.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.contentOrNull
                is JsonObject -> (element["url"] ?: element["image_url"] ?: element["src"])
                    ?.jsonPrimitive?.contentOrNull
                else -> null
            }
        }
    }

    // =============================== Filters ================================

    override fun getFilterList(data: JsonElement?): FilterList = getFilters()

    companion object {
        private const val API_URL = "https://api.damconuong.pw/api/v1"

        private const val LOCKED_CHAPTER_MESSAGE =
            "Cần đăng nhập bằng WebView (mở trang này trong trình duyệt trong app rồi đăng nhập) mới đọc được ảnh chương."
    }
}
