package org.koitharu.kotatsu.parsers.site.all

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
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
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import java.util.EnumSet

/**
 * MangaFire parser for the post-2026 site redesign.
 *
 * The old HTML + VRF (`/ajax/read/...`) endpoints were replaced by a public JSON API:
 * - GET /api/titles
 * - GET /api/titles/{hid}
 * - GET /api/titles/{hid}/chapters
 * - GET /api/chapters/{id}
 */
internal abstract class MangaFireParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	private val siteLang: String,
) : PagedMangaParser(context, source, 50) {

	override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("mangafire.to")

	private val apiHeaders: Headers by lazy {
		Headers.Builder()
			.add("Accept", "application/json")
			.add("Referer", "https://$domain/")
			.build()
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
		SortOrder.RELEVANCE,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = GENRES.map { (title, id) ->
			MangaTag(
				title = title,
				key = id,
				source = source,
			)
		}.toSet(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.ABANDONED,
			MangaState.PAUSED,
			MangaState.UPCOMING,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = "https://$domain/api/titles".toHttpUrl().newBuilder()
			.addQueryParameter("page", page.toString())
			.addQueryParameter("limit", pageSize.toString())
			.apply {
				val query = filter.query?.trim().orEmpty()
				if (query.isNotEmpty()) {
					addQueryParameter("keyword", query)
				}
				filter.tags.forEach { tag ->
					addQueryParameter("genres_in[]", tag.key)
				}
				filter.tagsExclude.forEach { tag ->
					addQueryParameter("genres_ex[]", tag.key)
				}
				filter.states.forEach { state ->
					addQueryParameter(
						"statuses[]",
						when (state) {
							MangaState.ONGOING -> "releasing"
							MangaState.FINISHED -> "finished"
							MangaState.ABANDONED -> "discontinued"
							MangaState.PAUSED -> "on_hiatus"
							MangaState.UPCOMING -> "not_yet_released"
							else -> return@forEach
						},
					)
				}
				// Prefer language-specific results when the API supports it.
				addQueryParameter("languages[]", siteLang)
				val sort = when {
					query.isNotEmpty() && order == SortOrder.RELEVANCE -> "relevance" to "desc"
					order == SortOrder.UPDATED -> "chapter_updated_at" to "desc"
					order == SortOrder.POPULARITY -> "views_30d" to "desc"
					order == SortOrder.RATING -> "score" to "desc"
					order == SortOrder.NEWEST -> "created_at" to "desc"
					order == SortOrder.ALPHABETICAL -> "title" to "asc"
					order == SortOrder.RELEVANCE -> "relevance" to "desc"
					else -> "chapter_updated_at" to "desc"
				}
				addQueryParameter("order[${sort.first}]", sort.second)
			}
			.build()

		val json = webClient.httpGet(url, apiHeaders).parseJson()
		val items = json.optJSONArray("items") ?: return emptyList()
		return buildList(items.length()) {
			for (i in 0 until items.length()) {
				val item = items.optJSONObject(i) ?: continue
				parseMangaListItem(item)?.let(::add)
			}
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val hid = extractHid(manga.url)
		val data = webClient.httpGet("https://$domain/api/titles/$hid", apiHeaders)
			.parseJson()
			.getJSONObject("data")

		val slug = data.optString("slug").nullIfEmpty()
		val mangaUrl = buildTitleUrl(hid, slug)
		val genres = data.optEntityList("genres")
		val themes = data.optEntityList("themes")
		val authors = data.optEntityTitles("authors") + data.optEntityTitles("artists")
		val tags = (genres + themes).mapNotNullTo(HashSet()) { entity ->
			val title = entity.optString("title").nullIfEmpty() ?: return@mapNotNullTo null
			val id = entity.opt("id")?.toString() ?: return@mapNotNullTo null
			MangaTag(title = title.toTitleCase(sourceLocale), key = id, source = source)
		}

		var isAdult = false
		var isSuggestive = false
		for (tag in tags) {
			when (tag.title.lowercase()) {
				"hentai" -> isAdult = true
				"ecchi" -> isSuggestive = true
			}
		}

		val altTitles = data.optJSONArray("altTitles")?.let { arr ->
			buildSet {
				for (i in 0 until arr.length()) {
					arr.optString(i).nullIfEmpty()?.let(::add)
				}
			}
		}.orEmpty()

		val rating = data.optDouble("rating", Double.NaN).let { value ->
			if (value.isNaN()) RATING_UNKNOWN else (value / 10.0).toFloat().coerceIn(0f, 1f)
		}

		val poster = data.optJSONObject("poster")
		val cover = poster?.optString("large")?.nullIfEmpty()
			?: poster?.optString("medium")?.nullIfEmpty()
			?: poster?.optString("small")?.nullIfEmpty()

		val descriptionHtml = data.optString("synopsisHtml").nullIfEmpty()
		val description = descriptionHtml?.let { Jsoup.parseBodyFragment(it).text() }

		return manga.copy(
			url = mangaUrl,
			publicUrl = mangaUrl.toAbsoluteUrl(domain),
			title = data.optString("title").ifEmpty { manga.title },
			altTitles = altTitles,
			coverUrl = cover ?: manga.coverUrl,
			largeCoverUrl = cover,
			authors = authors,
			tags = tags,
			description = description,
			rating = rating,
			contentRating = when {
				isAdult -> ContentRating.ADULT
				isSuggestive -> ContentRating.SUGGESTIVE
				else -> ContentRating.SAFE
			},
			state = when (data.optString("status").lowercase()) {
				"releasing" -> MangaState.ONGOING
				"finished" -> MangaState.FINISHED
				"discontinued" -> MangaState.ABANDONED
				"on_hiatus" -> MangaState.PAUSED
				"not_yet_released", "info" -> MangaState.UPCOMING
				else -> null
			},
			chapters = fetchAllChapters(hid, mangaUrl),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterId = extractChapterId(chapter.url)
		val data = webClient.httpGet("https://$domain/api/chapters/$chapterId", apiHeaders)
			.parseJson()
			.getJSONObject("data")
		val pages = data.optJSONArray("pages") ?: return emptyList()
		return buildList(pages.length()) {
			for (i in 0 until pages.length()) {
				val page = pages.optJSONObject(i) ?: continue
				val imageUrl = page.optString("url").nullIfEmpty() ?: continue
				add(
					MangaPage(
						id = generateUid(imageUrl),
						url = imageUrl,
						preview = null,
						source = source,
					),
				)
			}
		}
	}

	private suspend fun fetchAllChapters(hid: String, mangaUrl: String): List<MangaChapter> {
		val all = ArrayList<JSONObject>()
		var page = 1
		var lastPage = 1
		do {
			val url = "https://$domain/api/titles/$hid/chapters".toHttpUrl().newBuilder()
				.addQueryParameter("language", siteLang)
				.addQueryParameter("sort", "number")
				.addQueryParameter("order", "desc")
				.addQueryParameter("page", page.toString())
				.addQueryParameter("limit", "200")
				.build()
			val json = webClient.httpGet(url, apiHeaders).parseJson()
			val items = json.optJSONArray("items")
			if (items != null) {
				for (i in 0 until items.length()) {
					items.optJSONObject(i)?.let(all::add)
				}
			}
			lastPage = json.optJSONObject("meta")?.optInt("lastPage", 1) ?: 1
			page++
		} while (page <= lastPage)

		// API returns newest-first; mapChapters(reversed = true) expects that order for numbering.
		return all.mapChapters(reversed = true) { _, item ->
			val id = item.optLong("id", -1L).takeIf { it > 0 } ?: return@mapChapters null
			val number = item.optDouble("number", -1.0).toFloat()
			val name = item.optString("name").nullIfEmpty()
			val type = item.optString("type").nullIfEmpty() ?: "chapter"
			val createdAt = item.optLong("createdAt", 0L).let { ts ->
				when {
					ts <= 0L -> 0L
					// API returns unix seconds.
					ts < 10_000_000_000L -> ts * 1000L
					else -> ts
				}
			}
			val numberLabel = number.toString().removeSuffix(".0")
			val chapterPath = "$mangaUrl/$id-chapter-$numberLabel-$siteLang"
			MangaChapter(
				id = generateUid(chapterPath),
				title = buildString {
					append("Ch. ")
					append(numberLabel)
					if (!name.isNullOrBlank()) {
						append(" - ")
						append(name)
					}
				},
				number = number,
				volume = 0,
				url = chapterPath,
				scanlator = null,
				uploadDate = createdAt,
				branch = type.toTitleCase(sourceLocale),
				source = source,
			)
		}
	}

	private fun parseMangaListItem(item: JSONObject): Manga? {
		val hid = item.optString("hid").nullIfEmpty() ?: return null
		val slug = item.optString("slug").nullIfEmpty()
		val title = item.optString("title").nullIfEmpty() ?: return null
		val mangaUrl = item.optString("url").nullIfEmpty() ?: buildTitleUrl(hid, slug)
		val poster = item.optJSONObject("poster")
		val cover = poster?.optString("medium")?.nullIfEmpty()
			?: poster?.optString("large")?.nullIfEmpty()
			?: poster?.optString("small")?.nullIfEmpty()
		return Manga(
			id = generateUid(mangaUrl),
			url = mangaUrl,
			publicUrl = mangaUrl.toAbsoluteUrl(domain),
			title = title,
			coverUrl = cover,
			source = source,
			altTitles = emptySet(),
			largeCoverUrl = poster?.optString("large")?.nullIfEmpty(),
			authors = emptySet(),
			contentRating = null,
			rating = RATING_UNKNOWN,
			state = when (item.optString("status").lowercase()) {
				"releasing" -> MangaState.ONGOING
				"finished" -> MangaState.FINISHED
				"discontinued" -> MangaState.ABANDONED
				"on_hiatus" -> MangaState.PAUSED
				"not_yet_released" -> MangaState.UPCOMING
				else -> null
			},
			tags = emptySet(),
		)
	}

	/**
	 * Supports both new (`/title/{hid}-{slug}`) and legacy (`/manga/slug.{hid}`) paths
	 * so library entries created before the redesign can still load details.
	 */
	private fun extractHid(url: String): String {
		val lastPart = url.trimEnd('/').substringAfterLast('/')
		return when {
			'.' in lastPart -> lastPart.substringAfterLast('.')
			'-' in lastPart -> lastPart.substringBefore('-')
			else -> lastPart
		}
	}

	private fun extractChapterId(url: String): String {
		// New: .../8981099-chapter-353-en
		// Old: mangaId/type/lang/8981099
		return url.trimEnd('/').substringAfterLast('/').substringBefore('-')
	}

	private fun buildTitleUrl(hid: String, slug: String?): String {
		return if (slug.isNullOrEmpty()) {
			"/title/$hid"
		} else {
			"/title/$hid-$slug"
		}
	}

	private fun JSONObject.optEntityList(key: String): List<JSONObject> {
		val value = opt(key) ?: return emptyList()
		return when (value) {
			is JSONArray -> buildList {
				for (i in 0 until value.length()) {
					value.optJSONObject(i)?.let(::add)
				}
			}
			is JSONObject -> listOf(value)
			else -> emptyList()
		}
	}

	private fun JSONObject.optEntityTitles(key: String): Set<String> {
		return optEntityList(key).mapNotNullTo(HashSet()) {
			it.optString("title").nullIfEmpty()
		}
	}

	@MangaSourceParser("MANGAFIRE_EN", "MangaFire English", "en")
	class English(context: MangaLoaderContext) : MangaFireParser(context, MangaParserSource.MANGAFIRE_EN, "en")

	@MangaSourceParser("MANGAFIRE_ES", "MangaFire Spanish", "es")
	class Spanish(context: MangaLoaderContext) : MangaFireParser(context, MangaParserSource.MANGAFIRE_ES, "es")

	@MangaSourceParser("MANGAFIRE_ESLA", "MangaFire Spanish (Latim)", "es")
	class SpanishLatim(context: MangaLoaderContext) :
		MangaFireParser(context, MangaParserSource.MANGAFIRE_ESLA, "es-la")

	@MangaSourceParser("MANGAFIRE_FR", "MangaFire French", "fr")
	class French(context: MangaLoaderContext) : MangaFireParser(context, MangaParserSource.MANGAFIRE_FR, "fr")

	@MangaSourceParser("MANGAFIRE_JA", "MangaFire Japanese", "ja")
	class Japanese(context: MangaLoaderContext) : MangaFireParser(context, MangaParserSource.MANGAFIRE_JA, "ja")

	@MangaSourceParser("MANGAFIRE_PT", "MangaFire Portuguese", "pt")
	class Portuguese(context: MangaLoaderContext) : MangaFireParser(context, MangaParserSource.MANGAFIRE_PT, "pt")

	@MangaSourceParser("MANGAFIRE_PTBR", "MangaFire Portuguese (Brazil)", "pt")
	class PortugueseBR(context: MangaLoaderContext) :
		MangaFireParser(context, MangaParserSource.MANGAFIRE_PTBR, "pt-br")

	private companion object {
		// Genre IDs from the redesigned MangaFire filters API / Mihon extension.
		val GENRES = listOf(
			"Action" to "1",
			"Adventure" to "78",
			"Avant Garde" to "3",
			"Boys Love" to "4",
			"Comedy" to "5",
			"Demons" to "77",
			"Drama" to "6",
			"Ecchi" to "7",
			"Fantasy" to "79",
			"Girls Love" to "9",
			"Gourmet" to "10",
			"Harem" to "11",
			"Horror" to "530",
			"Isekai" to "13",
			"Iyashikei" to "531",
			"Josei" to "15",
			"Kids" to "532",
			"Magic" to "539",
			"Mahou Shoujo" to "533",
			"Martial Arts" to "534",
			"Mecha" to "19",
			"Military" to "535",
			"Music" to "21",
			"Mystery" to "22",
			"Parody" to "23",
			"Psychological" to "536",
			"Reverse Harem" to "25",
			"Romance" to "26",
			"School" to "73",
			"Sci-Fi" to "28",
			"Seinen" to "537",
			"Shoujo" to "30",
			"Shounen" to "31",
			"Slice of Life" to "538",
			"Space" to "33",
			"Sports" to "34",
			"Super Power" to "75",
			"Supernatural" to "76",
			"Suspense" to "37",
			"Thriller" to "38",
			"Vampire" to "39",
		)
	}
}
