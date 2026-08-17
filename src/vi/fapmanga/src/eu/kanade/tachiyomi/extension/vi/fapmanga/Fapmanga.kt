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
import keiyoushi.source.KeiSource

// Còn nợ logic thật: getPopularManga, getSearchMangaList, getPageList (chưa có task nào điền).
@Source
abstract class Fapmanga : KeiSource() {

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

    override suspend fun getPageList(chapter: SChapter): List<Page> = throw UnsupportedOperationException()
}
