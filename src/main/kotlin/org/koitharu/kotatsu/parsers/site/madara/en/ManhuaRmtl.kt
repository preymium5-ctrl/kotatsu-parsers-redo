package org.koitharu.kotatsu.parsers.site.madara.en

import org.jsoup.Jsoup
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl

/**
 * Parser for [ManhuaRMTL](https://manhuarmtl.com/).
 *
 * Standard Madara site. Multiple tags / tag exclusion come from [MadaraParser].
 * Page image `src` attributes often include a leading space and point at
 * `cdn.manhuarmmtl.com`, so [getPages] trims and keeps absolute CDN URLs.
 */
@MangaSourceParser("MANHUARMTL", "ManhuaRMTL", "en")
internal class ManhuaRmtl(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANHUARMTL, "manhuarmtl.com") {

	override val configKeyDomain = ConfigKey.Domain("manhuarmtl.com")

	// Prefer HTML listings — more reliable than ajax on this host.
	override val withoutAjax = true

	// Clean text synopsis instead of raw HTML fragments.
	override val selectDesc =
		"div.description-summary div.summary__content, div.summary_content div.manga-excerpt, " +
			"div.summary_content div.post-content_item > h5 + div, div.post-content div.manga-summary"

	override suspend fun getDetails(manga: Manga): Manga {
		val details = super.getDetails(manga)
		// Super stores HTML; convert to plain text so entities decode and markup is dropped.
		val description = details.description
			?.let { raw ->
				Jsoup.parseBodyFragment(raw).text()
					.replace(Regex("\\s+"), " ")
					.trim()
			}
			?.ifEmpty { null }
			?: details.description
		return details.copy(description = description)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		if (doc.selectFirst(selectRequiredLogin) != null) {
			throw AuthRequiredException(source)
		}
		val root = doc.body().selectFirst(selectBodyPage)
			?: doc.body().selectFirst("div.reading-content")
			?: throw ParseException("No image found", fullUrl)

		val images = root.select("$selectPage img, img.wp-manga-chapter-img")
		if (images.isEmpty()) {
			throw ParseException("No pages found", fullUrl)
		}
		return images.mapNotNull { img ->
			val raw = sequenceOf("data-src", "data-lazy-src", "src")
				.map { img.attr(it).trim() }
				.firstOrNull { it.isNotEmpty() && !it.startsWith("data:") }
				?: return@mapNotNull null
			// Drop site chrome (logo) that sometimes appears in the reading area.
			if (raw.contains("logo", ignoreCase = true) ||
				raw.contains("cropped-Manhuarm", ignoreCase = true)
			) {
				return@mapNotNull null
			}
			val url = if (raw.startsWith("http://") || raw.startsWith("https://")) {
				raw
			} else {
				raw.toAbsoluteUrl(domain)
			}
			MangaPage(
				id = generateUid(url),
				url = url.toRelativeUrl(domain).ifEmpty { url },
				preview = null,
				source = source,
			)
		}.ifEmpty {
			throw ParseException("No pages found", fullUrl)
		}
	}
}
