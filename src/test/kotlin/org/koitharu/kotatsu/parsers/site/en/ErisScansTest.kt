package org.koitharu.kotatsu.parsers.site.en

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
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
		val freeChapter = details.chapters.orEmpty().first { it.title?.startsWith(LOCK) != true }
		val pages = parser.getPages(freeChapter)

		assertTrue(pages.isNotEmpty())
		assertTrue(pages.all { it.url.startsWith("https://cdn.meowing.org/uploads/") })
	}

	/**
	 * Paid early access chapters used to be dropped from the list while the "latest chapter"
	 * shortcut on top of the page smuggled the newest one back in without a date, so series ended
	 * up with holes in the middle of the numbering.
	 */
	@Test
	fun listsEveryChapterOfTheSeries() = runTest(timeout = 2.minutes) {
		val parser = MangaLoaderContextMock.newParserInstance(MangaParserSource.ERISSCANS)
		val results = parser.getList(
			MangaSearchQuery.Builder()
				.criterion(QueryCriteria.Match(SearchableField.TITLE_NAME, "Samo"))
				.build(),
		)
		val chapters = parser.getDetails(results.first { it.title.equals("Samo", ignoreCase = true) })
			.chapters
			.orEmpty()
		assertTrue(chapters.isNotEmpty())
		// every row of the chapter list carries a release date, the navigation shortcuts do not
		assertTrue(chapters.all { it.uploadDate > 0L }) { "Chapters without an upload date: $chapters" }
		assertTrue(chapters.all { it.number > 0f }) { "Chapters without a number: $chapters" }
		assertEquals(chapters.size, chapters.distinctBy { it.number }.size, "Duplicated chapter numbers")
		assertEquals(chapters.map { it.number }.sorted(), chapters.map { it.number }, "Chapters are not sorted")
		assertEquals(
			chapters.first().number + chapters.size - 1,
			chapters.last().number,
			"The numbering has holes, some chapters are missing",
		)
	}

	private companion object {
		const val LOCK = "\uD83D\uDD12"
	}
}
