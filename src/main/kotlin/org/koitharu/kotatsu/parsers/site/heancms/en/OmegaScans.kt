package org.koitharu.kotatsu.parsers.site.heancms.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.heancms.HeanCms

@MangaSourceParser("OMEGASCANS", "OmegaScans", "en", ContentType.HENTAI)
internal class OmegaScans(context: MangaLoaderContext) :
	HeanCms(context, MangaParserSource.OMEGASCANS, "omegascans.org") {

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val pages = super.getPages(chapter)
		val sample = pages.firstOrNull { extractOmegaUploadPath(it.url) != null } ?: return pages
		val origin = findWorkingOrigin(sample.url) ?: return pages
		return pages.map { page ->
			val path = extractOmegaUploadPath(page.url) ?: return@map page
			page.copy(url = "$origin/$path")
		}
	}

	private suspend fun findWorkingOrigin(sampleUrl: String): String? {
		val path = extractOmegaUploadPath(sampleUrl) ?: return null
		for (candidate in omegaPageCandidates(sampleUrl)) {
			val isAvailable = runCatching {
				webClient.httpHead(candidate).use { response ->
					response.header("Content-Type")?.startsWith("image/", ignoreCase = true) == true
				}
			}.getOrDefault(false)
			if (isAvailable) return candidate.removeSuffix(path).trimEnd('/')
		}
		return null
	}
}

/**
 * Omega stores some series only on its media zone and others only on its API origin.
 * Keep the URL published by the chapter first, then try both current public origins.
 */
internal fun omegaPageCandidates(url: String): List<String> {
	val path = extractOmegaUploadPath(url) ?: return listOf(url)
	return listOf(
		url,
		"$OMEGA_MEDIA_ORIGIN/$path",
		"$OMEGA_API_ORIGIN/$path",
	).distinct()
}

internal fun extractOmegaUploadPath(url: String): String? =
	OMEGA_UPLOAD_PATH_REGEX.matchEntire(url)?.groupValues?.getOrNull(1)

private const val OMEGA_MEDIA_ORIGIN = "https://media.omegascans.org/file/zFSsXt"
private const val OMEGA_API_ORIGIN = "https://api.omegascans.org"

private val OMEGA_UPLOAD_PATH_REGEX = Regex(
	"""^https?://(?:media\.omegascans\.org/file/[^/]+|api\.omegascans\.org)/(uploads/series/.+)$""",
	RegexOption.IGNORE_CASE,
)
