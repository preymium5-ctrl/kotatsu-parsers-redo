package org.koitharu.kotatsu.parsers.site.heancms.en

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import kotlin.time.Duration.Companion.minutes

internal class OmegaScansTest {

	@Test
	fun keepsPublishedOriginThenAddsCurrentFallbacks() {
		val path = "uploads/series/example/chapter/01.jpg"
		assertEquals(
			listOf(
				"https://media.omegascans.org/file/old-zone/$path",
				"https://media.omegascans.org/file/zFSsXt/$path",
				"https://api.omegascans.org/$path",
			),
			omegaPageCandidates("https://media.omegascans.org/file/old-zone/$path"),
		)
	}

	@Test
	fun leavesUnrelatedOriginsUntouched() {
		val url = "https://cdn.example.org/uploads/series/example/chapter/01.jpg"
		assertEquals(listOf(url), omegaPageCandidates(url))
	}

	@Test
	fun selectsMediaOriginForInternHaenyeo() = runTest(timeout = 2.minutes) {
		val pages = parser().getPages(chapter("/series/intern-haenyeo/chapter-1"))
		assertTrue(pages.isNotEmpty())
		assertTrue(pages.all { it.url.startsWith("https://media.omegascans.org/file/zFSsXt/") })
	}

	@Test
	fun selectsApiOriginForAWonderfulNewWorld() = runTest(timeout = 2.minutes) {
		val pages = parser().getPages(chapter("/series/a-wonderful-new-world/chapter-2"))
		assertTrue(pages.isNotEmpty())
		assertTrue(pages.all { it.url.startsWith("https://api.omegascans.org/") })
	}

	private fun parser() = MangaLoaderContextMock.newParserInstance(MangaParserSource.OMEGASCANS)

	private fun chapter(url: String) = MangaChapter(
		id = url.hashCode().toLong(),
		title = null,
		number = 1f,
		volume = 0,
		url = url,
		scanlator = null,
		uploadDate = 0L,
		branch = null,
		source = MangaParserSource.OMEGASCANS,
	)
}
