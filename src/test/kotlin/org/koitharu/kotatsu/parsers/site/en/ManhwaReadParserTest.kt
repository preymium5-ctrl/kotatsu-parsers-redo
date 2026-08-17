package org.koitharu.kotatsu.parsers.site.en

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ManhwaReadParserTest {

	@Test
	fun `chapter links must belong to the selected manga slug`() {
		val selectedManga = "/manhwa/that-summer-night"

		assertTrue(
			isManhwaReadChapterPath(
				chapterPath = "/manhwa/that-summer-night/chapter-32",
				mangaPath = selectedManga,
			),
		)
		assertFalse(
			isManhwaReadChapterPath(
				chapterPath = "/manhwa/i-was-told-to-work-from-home/chapter-13",
				mangaPath = selectedManga,
			),
		)
		assertFalse(
			isManhwaReadChapterPath(
				chapterPath = "/manhwa/that-summer-night-special/chapter-1",
				mangaPath = selectedManga,
			),
		)
	}
}
