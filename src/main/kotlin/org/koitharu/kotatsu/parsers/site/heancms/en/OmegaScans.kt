package org.koitharu.kotatsu.parsers.site.heancms.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.heancms.HeanCms

@MangaSourceParser("OMEGASCANS", "OmegaScans", "en", ContentType.HENTAI)
internal class OmegaScans(context: MangaLoaderContext) :
	HeanCms(context, MangaParserSource.OMEGASCANS, "omegascans.org") {

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> =
		super.getPages(chapter).map { page ->
			page.copy(url = normalizeOmegaPageUrl(page.url))
		}
}

/**
 * Omega's page currently tries several origins in the browser. Its media `/file/{zone}/...`
 * preload can return 404 while the same upload remains available from the API origin.
 */
internal fun normalizeOmegaPageUrl(url: String): String {
	val path = OMEGA_MEDIA_FILE_REGEX.matchEntire(url)?.groupValues?.getOrNull(1) ?: return url
	return "https://api.omegascans.org/$path"
}

private val OMEGA_MEDIA_FILE_REGEX = Regex(
	"""^https?://media\.omegascans\.org/file/[^/]+/(.+)$""",
	RegexOption.IGNORE_CASE,
)
