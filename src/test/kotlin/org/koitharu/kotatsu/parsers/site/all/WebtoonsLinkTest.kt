package org.koitharu.kotatsu.parsers.site.all

import kotlinx.coroutines.test.runTest
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.SourceConfigMock

internal class WebtoonsLinkTest {

	@Test
	fun episodeLinksResolveToTheSeriesInTheSameLanguage() = runTest {
		for ((language, source) in listOf(
			"en" to MangaParserSource.WEBTOONS_EN,
			"fr" to MangaParserSource.WEBTOONS_FR,
			"zh-hant" to MangaParserSource.WEBTOONS_ZH,
		)) {
			val context = FixtureContext()
			val resolver = context.newLinkResolver(
				"https://www.webtoons.com/$language/fantasy/example/episode-7/viewer?title_no=123&episode_no=7#reader",
			)
			val manga = resolver.getManga()!!
			assertEquals(source, manga.source)
			assertEquals("123", manga.url)
			assertEquals("Fixture series", manga.title)
			assertEquals("https://www.webtoons.com/$language/fantasy/example/list?title_no=123", manga.publicUrl)
			assertEquals(listOf(1f, 2f), manga.chapters!!.map { it.number })
		}
	}

	@Test
	fun canvasLinksUseTheCanvasEpisodeEndpoint() = runTest {
		val resolver = FixtureContext().newLinkResolver(
			"https://www.webtoons.com/en/canvas/example/list?title_no=123",
		)
		assertEquals(2, resolver.getManga()!!.chapters!!.size)
	}

	@Test
	fun malformedOrDifferentLanguageLinksAreRejectedBeforeRequests() = runTest {
		val context = FixtureContext()
		val parser = context.newParserInstance(MangaParserSource.WEBTOONS_EN)
		for (path in listOf(
			"en/fantasy/example/list?title_no=0",
			"en/fantasy/example/list?title_no=invalid",
			"en/fantasy/example/list",
			"fr/fantasy/example/list?title_no=123",
			"en/search?title_no=123",
		)) {
			val resolver = context.newLinkResolver("https://www.webtoons.com/$path")
			assertNull(parser.resolveLink(resolver, resolver.link), path)
		}
	}

	@Test
	fun configuredDomainsAndDefaultDomainsBothResolve() = runTest {
		val context = FixtureContext(customDomain = "reader.fixture.invalid")
		assertEquals(MangaParserSource.WEBTOONS_EN, context.newLinkResolver("https://reader.fixture.invalid/en/example/list?title_no=123").getSource())
		assertEquals(MangaParserSource.WEBTOONS_EN, context.newLinkResolver("https://www.webtoons.com/en/example/list?title_no=123").getSource())
	}

	private class FixtureContext(private val customDomain: String? = null) : MangaLoaderContext() {
		override val cookieJar = CookieJar.NO_COOKIES
		override val httpClient = OkHttpClient.Builder().addInterceptor { chain ->
			val request = chain.request()
			val body = when {
				request.url.encodedPath.endsWith("/list") -> {
					check(request.url.queryParameter("title_no") == "123")
					check(request.url.queryParameter("episode_no") == null)
					check(request.url.fragment == null)
					"<html><head><meta property='og:title' content='Fixture series'></head><body>Series</body></html>"
				}
				request.url.encodedPath in listOf("/api/v1/webtoon/123/episodes", "/api/v1/canvas/123/episodes") ->
					"""{"result":{"episodeList":[{"episodeNo":2,"episodeTitle":"Second","viewerLink":"https://www.webtoons.com/episode2","exposureDateMillis":2000},{"episodeNo":1,"episodeTitle":"First","viewerLink":"https://www.webtoons.com/episode1","exposureDateMillis":1000}]}}"""
				else -> error("Unexpected request: ${request.url}")
			}
			Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
				.code(200).message("OK").body(body.toResponseBody()).build()
		}.build()

		override fun getConfig(source: MangaSource): MangaSourceConfig = if (customDomain != null && source == MangaParserSource.WEBTOONS_EN) {
			object : MangaSourceConfig {
				@Suppress("UNCHECKED_CAST")
				override fun <T> get(key: ConfigKey<T>): T = if (key is ConfigKey.Domain) customDomain as T else key.defaultValue
			}
		} else SourceConfigMock()
		override fun getDefaultUserAgent(): String = "Fixture"
		@Suppress("OVERRIDE_DEPRECATION")
		override suspend fun evaluateJs(script: String): String? = error("Unexpected JavaScript")
		override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String? = error("Unexpected JavaScript")
		override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response = error("Unexpected image")
		override fun createBitmap(width: Int, height: Int): Bitmap = error("Unexpected image")
	}
}
