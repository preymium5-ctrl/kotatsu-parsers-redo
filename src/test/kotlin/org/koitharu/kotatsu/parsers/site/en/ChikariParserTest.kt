package org.koitharu.kotatsu.parsers.site.en

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder

internal class ChikariParserTest {

	@Test
	fun genreLabelsStayReadableAndFiltersSendTheApiSlug() = runTest {
		val parser = FixtureContext().newParserInstance(MangaParserSource.CHIKARI)
		val tag = parser.getFilterOptions().availableTags.single()
		assertEquals("Science Fiction", tag.title)
		assertEquals("science-fiction", tag.key)
		val manga = parser.getList(0, SortOrder.UPDATED, MangaListFilter(tags = setOf(tag))).single()
		assertEquals(tag, manga.tags.single())
	}

	private class FixtureContext : MangaLoaderContext() {
		override val cookieJar = MangaLoaderContextMock.cookieJar
		override val httpClient = OkHttpClient.Builder().addInterceptor { chain ->
			val request = chain.request()
			val body = when (request.url.encodedPath) {
				"/api/genres" -> """[{"slug":"science-fiction","name":"Science Fiction"}]"""
				"/api/series" -> {
					assertEquals("science-fiction", request.url.queryParameter("genre"))
					"""{"items":[{"slug":"fixture","title":"Fixture","genres":[{"slug":"science-fiction","name":"Science Fiction"}]}]}"""
				}
				else -> error("Unexpected request: ${request.url}")
			}
			Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
				.code(200).message("OK").body(body.toResponseBody()).build()
		}.build()
		override fun getConfig(source: MangaSource) = MangaLoaderContextMock.getConfig(source)
		override fun getDefaultUserAgent() = "Fixture"
		@Suppress("OVERRIDE_DEPRECATION")
		override suspend fun evaluateJs(script: String): String? = error("Unexpected JavaScript")
		override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String? = error("Unexpected JavaScript")
		override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response = error("Unexpected image")
		override fun createBitmap(width: Int, height: Int): Bitmap = error("Unexpected image")
	}
}
