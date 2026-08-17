package eu.kanade.tachiyomi.extension.vi.fapmanga

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource

// Task 3 — khung rong, chua co logic that. TODO se duoc dien o cac task sau.
@Source
abstract class Fapmanga : KeiSource() {

    override val supportsLatest = true

    override suspend fun getPopularManga(page: Int): MangasPage = TODO("not yet implemented")

    override suspend fun getLatestUpdates(page: Int): MangasPage = TODO("not yet implemented")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = TODO("not yet implemented")

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = TODO("not yet implemented")

    override suspend fun getPageList(chapter: SChapter): List<Page> = TODO("not yet implemented")
}
