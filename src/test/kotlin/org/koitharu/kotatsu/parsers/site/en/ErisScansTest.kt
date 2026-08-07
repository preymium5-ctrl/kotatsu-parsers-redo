package org.koitharu.kotatsu.parsers.site.en

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQuery
import org.koitharu.kotatsu.parsers.model.search.QueryCriteria
import org.koitharu.kotatsu.parsers.model.search.SearchableField
import kotlin.time.Duration.Companion.minutes

internal class ErisScansTest {

	@Test
	fun loadsSearchDetailsAndFreeChapterPages() = runTest(timeout = 2.minutes) {
		val parser = MangaLoaderContextMock.newParserInstance(MangaParserSource.ERISSCANS)
		val results = parser.getList(
			MangaSearchQuery.Builder()
				.criterion(QueryCriteria.Match(SearchableField.TITLE_NAME, "Samo"))
				.build(),
		)
		assertTrue(results.isNotEmpty())

		val details = parser.getDetails(results.first { it.title.equals("Samo", ignoreCase = true) })
		val freeChapter = details.chapters.orEmpty().first()
		val pages = parser.getPages(freeChapter)

		assertTrue(pages.isNotEmpty())
		assertTrue(pages.all { it.url.startsWith("https://cdn.meowing.org/uploads/") })
	}
}
