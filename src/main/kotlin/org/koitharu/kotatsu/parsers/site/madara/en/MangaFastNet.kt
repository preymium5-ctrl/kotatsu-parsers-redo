package org.koitharu.kotatsu.parsers.site.madara.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.requireSrc
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl

/**
 * MangaFast.net entry — same outage as ManhuaFast.
 * Temporarily defaults to manhuahot.com so this source still works.
 */
@MangaSourceParser("MANGAFASTNET", "MangaFast.net", "en")
internal class MangaFastNet(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANGAFASTNET, "manhuahot.com") {

	override val configKeyDomain = ConfigKey.Domain(
		"manhuahot.com",
		"manhuaplus.com",
		"manhuafast.net",
		"manhuafast.com",
	)

	override val withoutAjax = true

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		val root = doc.body().selectFirst("div.main-col-inner div.reading-content")
			?: doc.body().selectFirst("div.reading-content")
			?: doc.body().selectFirst("div.entry-content")
			?: throw ParseException("Root not found", fullUrl)

		val images = root.select("img")
		if (images.isEmpty()) {
			throw ParseException("No pages found", fullUrl)
		}
		return images.map { img ->
			val url = img.requireSrc().toRelativeUrl(domain)
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}
}
