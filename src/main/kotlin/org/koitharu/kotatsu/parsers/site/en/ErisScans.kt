package org.koitharu.kotatsu.parsers.site.en

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("ERISSCANS", "Eris Scans", "en", ContentType.HENTAI)
internal class ErisScans(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.ERISSCANS, PAGE_SIZE) {

	override val configKeyDomain = ConfigKey.Domain("erisscans.com")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = false,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val doc = webClient.httpGet("https://$domain/series/").parseHtml()
		val tags = doc.select("a[href*='/series/?genre=']").mapNotNullToSet { a ->
			val title = a.attr("title").ifBlank { a.text() }.trim()
			val key = a.attr("href").substringAfter("genre=", "").urlDecode().trim()
			if (title.isEmpty() || key.isEmpty()) return@mapNotNullToSet null
			MangaTag(key = key, title = title.toTitleCase(sourceLocale), source = source)
		}
		return MangaListFilterOptions(availableTags = tags)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val tag = filter.tags.oneOrThrowIfMany()?.key
		val hasFilters = !filter.query.isNullOrBlank() || !tag.isNullOrBlank()
		val url = buildString {
			append("https://")
			append(domain)
			append(if (order == SortOrder.UPDATED && !hasFilters) "/latest/" else "/series/")
			val params = buildList {
				filter.query?.takeIf { it.isNotBlank() }?.let { add("q=${it.urlEncoded()}") }
				tag?.takeIf { it.isNotBlank() }?.let { add("genre=${it.urlEncoded()}") }
			}
			if (params.isNotEmpty()) append('?').append(params.joinToString("&"))
		}
		val filtered = parseMangaList(webClient.httpGet(url).parseHtml()).filter { manga ->
			val matchesQuery = filter.query?.trim()?.takeIf { it.isNotEmpty() }?.let { query ->
				manga.title.contains(query, ignoreCase = true) ||
					manga.altTitles.any { it.contains(query, ignoreCase = true) }
			} ?: true
			val matchesTag = tag?.let { selected -> manga.tags.any { it.key.equals(selected, ignoreCase = true) } } ?: true
			matchesQuery && matchesTag
		}
		val all = when (order) {
			SortOrder.ALPHABETICAL -> filtered.sortedBy { it.title.lowercase(sourceLocale) }
			SortOrder.ALPHABETICAL_DESC -> filtered.sortedByDescending { it.title.lowercase(sourceLocale) }
			else -> filtered
		}
		return all.drop((page - 1).coerceAtLeast(0) * PAGE_SIZE).take(PAGE_SIZE)
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		val result = LinkedHashMap<String, Manga>()
		for (coverLink in doc.select("a[href^=/series/][title]")) {
			val coverStyle = coverLink.attr("style").takeIf { "background-image" in it }
				?: coverLink.selectFirst("[style*=background-image]")?.attr("style")
				?: continue
			val url = coverLink.attrAsRelativeUrlOrNull("href") ?: continue
			if (url in result) continue
			val card = coverLink.parents().firstOrNull { it.tagName() == "button" || it.hasClass("latest-poster") }
				?: coverLink.parent()
				?: continue
			val title = coverLink.attr("title").trim().ifEmpty { continue }
			val fullTitle = card.attr("title").trim()
			val altTitles = setOfNotNull(fullTitle.removePrefix(title).trim().nullIfEmpty())
			val cover = BACKGROUND_URL_REGEX.find(coverStyle)?.groupValues?.getOrNull(1)?.trim('\'', '"')
			val labels = card.select("span.capitalize").map { it.text().trim().lowercase() }
			val tags = QUOTED_VALUE_REGEX.findAll(card.attr("tags")).mapNotNull { match ->
				val tagTitle = match.groupValues.getOrNull(1)?.trim().orEmpty()
				if (tagTitle.isEmpty()) null else MangaTag(tagTitle.lowercase(), tagTitle, source)
			}.toSet()
			val status = card.attr("data-status").trim().lowercase()
			result[url] = Manga(
				id = generateUid(url),
				title = title,
				altTitles = altTitles,
				url = url,
				publicUrl = url.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.ADULT,
				coverUrl = cover,
				tags = tags,
				state = when {
					status == "completed" || "completed" in labels -> MangaState.FINISHED
					status == "hiatus" || "hiatus" in labels -> MangaState.PAUSED
					else -> MangaState.ONGOING
				},
				authors = emptySet(),
				source = source,
			)
		}
		return result.values.toList()
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val chapters = LinkedHashMap<String, MangaChapter>()
		for (a in doc.selectChapterRows()) {
			val url = a.attrAsRelativeUrlOrNull("href") ?: continue
			if (url in chapters) continue
			val title = a.attr("title").trim()
				.ifEmpty { a.attr("alt").trim() }
				.ifEmpty { a.selectFirst("span.truncate")?.text()?.trim().orEmpty() }
			chapters[url] = MangaChapter(
				id = generateUid(url),
				title = if (a.isLockedChapter()) LOCKED_TITLE_PREFIX + title else title,
				number = CHAPTER_NUMBER_REGEX.find(title)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0f,
				volume = 0,
				url = url,
				scanlator = null,
				uploadDate = parseChapterDate(a.attr("d")),
				branch = null,
				source = source,
			)
		}
		val tags = doc.select("a[href*='/series/?genre=']").mapNotNullToSet { a ->
			val title = a.attr("title").ifBlank { a.text() }.trim()
			val key = a.attr("href").substringAfter("genre=", "").urlDecode().trim()
			if (title.isEmpty() || key.isEmpty()) null else MangaTag(key, title, source)
		}
		val description = doc.selectFirst("meta[name=description]")?.attr("content")
			?.substringBefore(" - ErisScans")?.trim()
		val altTitles = doc.select("span.select-all").mapNotNullToSet { it.textOrNull() }
		val authors = buildSet {
			doc.selectFirst("[title=Author] span")?.textOrNull()?.let(::add)
			doc.selectFirst("[title=Artist] span")?.textOrNull()?.let(::add)
		}
		return manga.copy(
			title = doc.selectFirst("h1")?.textOrNull() ?: manga.title,
			altTitles = altTitles,
			description = description,
			coverUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null } ?: manga.coverUrl,
			tags = tags,
			state = when (doc.selectFirst("[title=Status] span")?.text()?.trim()?.lowercase()) {
				"completed" -> MangaState.FINISHED
				"hiatus" -> MangaState.PAUSED
				"dropped" -> MangaState.ABANDONED
				else -> MangaState.ONGOING
			},
			authors = authors,
			chapters = chapters.values.sortedBy { it.number },
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val container = doc.selectFirst("#pages")
			?: throw ParseException(
				"This chapter is locked and has to be unlocked with coins on the website first.",
				chapter.url,
			)
		return container.select("img.myImage[uid]").mapNotNull { img ->
			val uid = img.attr("uid").trim().ifEmpty { return@mapNotNull null }
			val url = "https://cdn.meowing.org/uploads/$uid"
			MangaPage(id = generateUid(url), url = url, preview = null, source = source)
		}
	}

	/**
	 * The chapter list lives in the `#chapters` grid, where every row carries the release date in
	 * `d` and the coin price in `c`. The "first chapter" / "latest chapter" shortcuts above the
	 * list link to the same urls but without any metadata, so they must not be parsed as rows:
	 * otherwise the newest locked chapter slips into the list with an unknown date while the
	 * other locked ones are handled by [isLockedChapter], which leaves holes in the numbering.
	 */
	private fun Document.selectChapterRows(): List<Element> {
		val rows = select("#chapters a[href^=/chapter/]")
		return if (rows.isEmpty()) select("a[href^=/chapter/][d]") else rows
	}

	/**
	 * Chapters costing more than one coin are paid early access: the site draws a lock over the
	 * thumbnail and the reader page contains no images. They are still listed so that nothing
	 * looks missing compared to the website, but the title is marked to show they are not free.
	 */
	private fun Element.isLockedChapter(): Boolean =
		(attr("c").toIntOrNull() ?: 0) > 1 || selectFirst("img[src*=lock]") != null

	private fun parseChapterDate(raw: String): Long {
		val date = raw.trim()
		chapterDateFormat.parseSafe(date).takeIf { it != 0L }?.let { return it }
		val amount = RELATIVE_DATE_REGEX.find(date)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return 0L
		val field = when {
			date.contains("minute", ignoreCase = true) -> Calendar.MINUTE
			date.contains("hour", ignoreCase = true) -> Calendar.HOUR_OF_DAY
			date.contains("day", ignoreCase = true) -> Calendar.DAY_OF_YEAR
			date.contains("week", ignoreCase = true) -> Calendar.WEEK_OF_YEAR
			date.contains("month", ignoreCase = true) -> Calendar.MONTH
			date.contains("year", ignoreCase = true) -> Calendar.YEAR
			else -> return 0L
		}
		return Calendar.getInstance().apply { add(field, -amount) }.timeInMillis
	}

	private companion object {
		const val PAGE_SIZE = 20
		const val LOCKED_TITLE_PREFIX = "\uD83D\uDD12 " // lock emoji
		val BACKGROUND_URL_REGEX = Regex("""background-image\s*:\s*url\(([^)]+)\)""", RegexOption.IGNORE_CASE)
		val CHAPTER_NUMBER_REGEX = Regex("""(?:chapter|ch\.?)[^\d]*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
		val RELATIVE_DATE_REGEX = Regex("""(\d+)\s+(?:minute|hour|day|week|month|year)s?\s+ago""", RegexOption.IGNORE_CASE)
		val QUOTED_VALUE_REGEX = Regex("\"([^\"]+)\"")
		val chapterDateFormat = SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH)
	}
}
