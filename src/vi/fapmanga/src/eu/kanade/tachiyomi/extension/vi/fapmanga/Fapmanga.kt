package eu.kanade.tachiyomi.extension.vi.fapmanga

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource

// Khung rong, chua co logic that; cac phuong thuc duoc dien o cac task sau.
@Source
abstract class Fapmanga : KeiSource() {

    override suspend fun getPopularManga(page: Int): MangasPage = throw UnsupportedOperationException()

    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = throw UnsupportedOperationException()

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = throw UnsupportedOperationException()

    override suspend fun getPageList(chapter: SChapter): List<Page> = throw UnsupportedOperationException()
}
