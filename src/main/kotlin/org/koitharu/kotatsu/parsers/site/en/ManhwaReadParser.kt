package org.koitharu.kotatsu.parsers.site.en

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

/**
 * Parser for [ManhwaRead](https://manhwaread.com/).
 *
 * Custom mangomic/manhwaread theme (not Madara). Chapter images are embedded as
 * base64 JSON in `var chapterData = { base, data }`.
 */
@MangaSourceParser("MANHWAREAD", "ManhwaRead", "en", type = ContentType.HENTAI)
internal class ManhwaReadParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANHWAREAD, 24) {

	override val configKeyDomain = ConfigKey.Domain("manhwaread.com")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
		SortOrder.RATING,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.ABANDONED,
			MangaState.PAUSED,
			MangaState.UPCOMING,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query?.trim().orEmpty()
		val url = when {
			query.isNotEmpty() -> {
				buildString {
					append("https://$domain/")
					if (page > 1) append("page/$page/")
					append("?s=")
					append(query.urlEncoded())
				}
			}
			filter.tags.isNotEmpty() -> {
				// First tag is browsed server-side; remaining tags AND-filtered client-side.
				val primary = filter.tags.first()
				buildString {
					append("https://$domain/genre/")
					append(primary.key)
					append('/')
					if (page > 1) {
						append("page/")
						append(page)
						append('/')
					}
					append("?orderby=latest&sortby=")
					append(sortParam(order))
				}
			}
			else -> {
				buildString {
					append("https://$domain/manhwa/")
					if (page > 1) {
						append("page/")
						append(page)
						append('/')
					}
					append("?orderby=latest&sortby=")
					append(sortParam(order))
				}
			}
		}

		val doc = webClient.httpGet(url).parseHtml()
		return applyLocalFilters(parseMangaList(doc), filter)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()

		val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
			?.substringBefore(" - #")
			?.substringBefore(" | ")
			?.trim()
			?: doc.selectFirst("h1, .manga-title")?.text()?.trim()
			?: manga.title

		val description = extractDescription(doc)
		val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")
			?: manga.coverUrl

		val tags = doc.select("a[href*=/genre/]").mapToSet { a ->
			val key = a.attr("href").trimEnd('/').substringAfterLast('/')
			MangaTag(
				key = key,
				title = a.text().trim().ifEmpty { key }.toTitleCase(sourceLocale),
				source = source,
			)
		}.ifEmpty { manga.tags }

		val authors = doc.select(".author-artist span, a[href*=/author/], a[href*=/artist/]")
			.mapNotNull { it.text().trim().nullIfEmpty() }
			.toSet()
			.ifEmpty { manga.authors }

		val state = doc.selectFirst(".manga-status[data-status]")?.attr("data-status")?.let { status ->
			when (status.lowercase(Locale.ROOT)) {
				"ongoing", "incomplete" -> MangaState.ONGOING
				"completed", "complete", "end" -> MangaState.FINISHED
				"canceled", "cancelled", "dropped" -> MangaState.ABANDONED
				"on-hold", "hiatus", "onhold" -> MangaState.PAUSED
				"upcoming" -> MangaState.UPCOMING
				else -> null
			}
		} ?: manga.state

		val isAdult = tags.any {
			val k = it.key.lowercase(Locale.ROOT)
			k == "adult" || k == "hentai" || k == "smut" || k == "mature"
		} || doc.selectFirst(".manga-status, body")?.classNames()?.any {
			it.contains("adult", ignoreCase = true)
		} == true

		val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.US)
		val chapters = doc.select("a.chapter-item[href*=/chapter-], a.chapter-item[href*=/manhwa/]")
			.mapNotNull { a ->
				val href = a.attr("href").trim()
				if (href.isEmpty() || !href.contains("/chapter-")) return@mapNotNull null
				val path = if (href.startsWith("http")) {
					href.removePrefix("https://$domain").removePrefix("http://$domain")
				} else {
					href
				}
				val text = a.text().replace(Regex("\\s+"), " ").trim()
				val dateText = Regex("""(\d{2}/\d{2}/\d{4})""").find(text)?.groupValues?.get(1)
				val number = Regex("""(?i)chapter\s*([0-9]+(?:\.[0-9]+)?)""")
					.find(text)?.groupValues?.get(1)?.toFloatOrNull()
					?: Regex("""/chapter-([0-9]+(?:-[0-9]+)?)""")
						.find(path)?.groupValues?.get(1)
						?.substringBefore('-')
						?.toFloatOrNull()
					?: 0f
				val titleText = text
					.replace(Regex("""\d{2}/\d{2}/\d{4}"""), "")
					.trim()
					.nullIfEmpty()
				Triple(path, titleText, dateText to number)
			}
			.distinctBy { it.first }
			.sortedBy { it.third.second }
			.mapChapters(reversed = true) { _, (path, titleText, meta) ->
				val (dateText, number) = meta
				MangaChapter(
					id = generateUid(path),
					title = titleText,
					number = number,
					volume = 0,
					url = path,
					scanlator = null,
					uploadDate = dateText?.let {
						runCatching { dateFormat.parse(it)?.time }.getOrNull()
					} ?: 0L,
					branch = null,
					source = source,
				)
			}

		return manga.copy(
			title = title,
			coverUrl = cover,
			largeCoverUrl = cover,
			description = description,
			tags = tags,
			authors = authors,
			state = state,
			contentRating = if (isAdult) ContentRating.ADULT else ContentRating.SAFE,
			chapters = chapters,
			publicUrl = fullUrl,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val html = webClient.httpGet(fullUrl).parseHtml().outerHtml()
		val match = CHAPTER_DATA_REGEX.find(html)
			?: throw ParseException("chapterData not found", fullUrl)
		val chapterJson = try {
			JSONObject(match.groupValues[1])
		} catch (_: Exception) {
			throw ParseException("Invalid chapterData JSON", fullUrl)
		}
		val dataB64 = chapterJson.optString("data").nullIfEmpty()
			?: throw ParseException("chapterData.data missing", fullUrl)
		val base = chapterJson.optString("base").trim().trimEnd('/')
		val pagesJson = try {
			context.decodeBase64(dataB64).toString(Charsets.UTF_8)
		} catch (_: Exception) {
			throw ParseException("Invalid chapterData payload", fullUrl)
		}
		val array = JSONArray(pagesJson)
		if (array.length() == 0) {
			throw ParseException("No pages in chapterData", fullUrl)
		}
		val result = ArrayList<MangaPage>(array.length())
		for (i in 0 until array.length()) {
			val obj = array.optJSONObject(i) ?: continue
			val src = obj.optString("src").nullIfEmpty() ?: continue
			val url = if (src.startsWith("http://") || src.startsWith("https://")) {
				src
			} else {
				"$base/${src.trimStart('/')}"
			}
			result += MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
		if (result.isEmpty()) {
			throw ParseException("No pages found", fullUrl)
		}
		return result
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		val items = doc.select("div.manga-item.loop-item, div.manga-item")
		if (items.isEmpty()) return emptyList()
		return items.mapNotNull { parseMangaItem(it) }
	}

	private fun parseMangaItem(item: Element): Manga? {
		val link = item.selectFirst("a.manga-item__link[href]")
			?: item.selectFirst("a[href*=/manhwa/]")
			?: return null
		val href = link.attr("href").trim()
		if (href.isEmpty() || href.endsWith("/manhwa/") || href.contains("/page/")) return null
		val path = if (href.startsWith("http")) {
			href.removePrefix("https://$domain").removePrefix("http://$domain")
		} else {
			href
		}
		if (!path.contains("/manhwa/")) return null

		val title = link.text().trim().ifEmpty {
			item.selectFirst("img.manga-item__img-inner, img")?.attr("alt")?.trim().orEmpty()
		}
		if (title.isEmpty()) return null

		val cover = item.selectFirst("img.manga-item__img-inner, img")
			?.let { img ->
				sequenceOf("data-src", "data-lazy-src", "src")
					.map { img.attr(it).trim() }
					.firstOrNull { it.isNotEmpty() && !it.startsWith("data:") }
			}

		val tags = item.select(".manga-item__genres span").mapToSet { span ->
			val name = span.text().trim()
			MangaTag(
				key = name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-'),
				title = name.toTitleCase(sourceLocale),
				source = source,
			)
		}

		val authors = item.select(".author-artist span")
			.mapNotNull { it.text().trim().nullIfEmpty() }
			.toSet()

		val isAdult = tags.any {
			val k = it.key
			k == "adult" || k == "hentai" || k == "smut" || k == "mature"
		}

		return Manga(
			id = generateUid(path),
			url = path,
			publicUrl = path.toAbsoluteUrl(domain),
			title = title,
			coverUrl = cover,
			largeCoverUrl = cover,
			altTitles = item.selectFirst(".alt-title")?.text()?.trim()
				?.split('|')
				?.mapNotNull { it.trim().nullIfEmpty() }
				?.toSet()
				.orEmpty(),
			authors = authors,
			tags = tags,
			rating = RATING_UNKNOWN,
			state = null,
			contentRating = if (isAdult) ContentRating.ADULT else ContentRating.SAFE,
			source = source,
			description = null,
		)
	}

	/**
	 * Prefer the on-page synopsis (`.manga-desc__content`). Reject SEO meta
	 * boilerplate ("Read X also known as…").
	 */
	private fun extractDescription(doc: Document): String? {
		val bodyText = doc.selectFirst(".manga-desc__content")?.text()?.trim()
		if (!bodyText.isNullOrEmpty() && !isSeoDescription(bodyText)) {
			return bodyText
		}
		// Fallbacks that are still real synopsis-like text
		val alt = doc.select(".manga-desc p, .manga-summary, .entry-content p")
			.map { it.text().trim() }
			.firstOrNull { it.length >= 40 && !isSeoDescription(it) }
		if (!alt.isNullOrEmpty()) return alt

		// Last resort: og:description only if it is not pure SEO filler
		val og = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
		return og?.takeIf { it.length >= 40 && !isSeoDescription(it) }
	}

	private fun isSeoDescription(text: String): Boolean {
		val lower = text.lowercase(Locale.ROOT)
		return lower.startsWith("read ") &&
			(lower.contains("also known as") ||
				lower.contains("online for free") ||
				lower.contains("manhwaread") ||
				lower.contains("this series was written by"))
	}

	private fun applyLocalFilters(list: List<Manga>, filter: MangaListFilter): List<Manga> {
		if (filter.tags.isEmpty() && filter.tagsExclude.isEmpty()) return list
		val includeKeys = filter.tags.mapToSet { it.key.lowercase(Locale.ROOT) }
		val excludeKeys = filter.tagsExclude.mapToSet { it.key.lowercase(Locale.ROOT) }
		// When browsing a single genre URL, the primary tag is already applied server-side.
		val extraInclude = if (includeKeys.size <= 1) emptySet() else includeKeys
		return list.filter { manga ->
			val mangaKeys = manga.tags.mapToSet { it.key.lowercase(Locale.ROOT) }
			val titles = manga.tags.mapToSet { it.title.lowercase(Locale.ROOT) }
			fun hasTag(key: String): Boolean {
				if (key in mangaKeys) return true
				// Genre spans on cards use display names; keys may differ slightly.
				val pretty = key.replace('-', ' ')
				return pretty in titles || manga.tags.any {
					it.title.lowercase(Locale.ROOT).replace(' ', '-') == key
				}
			}
			(extraInclude.isEmpty() || extraInclude.all { hasTag(it) }) &&
				(excludeKeys.isEmpty() || excludeKeys.none { hasTag(it) })
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/genre-index/").parseHtml()
		return doc.select("a[href*=/genre/]").mapToSet { a ->
			val key = a.attr("href").trimEnd('/').substringAfterLast('/')
			val title = a.ownText().trim().ifEmpty {
				a.text().replace(Regex("""[\d.,KkMm]+$"""), "").trim()
			}.ifEmpty { key }
			MangaTag(
				key = key,
				title = title.toTitleCase(sourceLocale),
				source = source,
			)
		}.filter { it.key.isNotEmpty() && it.key != "genre" }.toSet()
	}

	private fun sortParam(order: SortOrder): String = when (order) {
		SortOrder.POPULARITY -> "daily_top"
		SortOrder.NEWEST -> "new"
		SortOrder.ALPHABETICAL -> "alphabet"
		SortOrder.RATING -> "rating"
		else -> "release"
	}

	private companion object {
		/** Captures the `{...}` object assigned to `var chapterData`. */
		val CHAPTER_DATA_REGEX = Regex(
			"""var\s+chapterData\s*=\s*(\{.*?\})\s*;""",
			setOf(RegexOption.DOT_MATCHES_ALL),
		)
	}
}
