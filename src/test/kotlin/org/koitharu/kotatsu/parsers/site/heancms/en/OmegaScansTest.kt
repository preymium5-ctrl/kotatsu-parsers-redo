package org.koitharu.kotatsu.parsers.site.heancms.en

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class OmegaScansTest {

	@Test
	fun replacesBrokenMediaFileOriginWithApiOrigin() {
		assertEquals(
			"https://api.omegascans.org/uploads/series/example/chapter/01.jpg",
			normalizeOmegaPageUrl(
				"https://media.omegascans.org/file/zESsXt/uploads/series/example/chapter/01.jpg",
			),
		)
	}

	@Test
	fun leavesOtherOriginsUntouched() {
		val url = "https://cdn.example.org/uploads/series/example/chapter/01.jpg"
		assertEquals(url, normalizeOmegaPageUrl(url))
	}
}
