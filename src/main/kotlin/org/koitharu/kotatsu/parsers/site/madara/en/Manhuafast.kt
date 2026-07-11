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
 * ManhuaFast source entry.
 *
 * Official domains (manhuafast.com / .net) have been offline / Cloudflare-blocked.
 * Default is temporarily pointed at [manhuaplus.com] (compatible Madara mirror) so the
 * source keeps working. Users can switch domain in source settings if the original returns:
 * - manhuaplus.com (working temporary mirror)
 * - manhuahot.com (alternate Madara manhua site)
 * - manhuafast.com / manhuafast.net (original, when online)
 */
@MangaSourceParser("MANHUAFAST", "ManhuaFast", "en")
internal class Manhuafast(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANHUAFAST, "manhuaplus.com") {

	override val configKeyDomain = ConfigKey.Domain(
		"manhuaplus.com",
		"manhuahot.com",
		"manhuafast.com",
		"manhuafast.net",
	)

	// Prefer HTML listings — more reliable across these Madara hosts.
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
