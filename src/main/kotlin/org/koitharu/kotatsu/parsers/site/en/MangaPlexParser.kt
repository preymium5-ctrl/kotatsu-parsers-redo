package org.koitharu.kotatsu.parsers.site.en

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
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
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJsonArray
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

/**
 * Parser for [MangaPlex](https://mangaplex.com/) / [beta.mangaplex.com](https://beta.mangaplex.com/).
 *
 * Catalog/search/genres: beta.mangaplex.com (HTML cards + chapter JSON API).
 * Chapter images: WordPress posts on mangaplex.com (absolute `link` from the API).
 */
@MangaSourceParser("MANGAPLEX", "MangaPlex", "en", type = ContentType.MANGA)
internal class MangaPlexParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANGAPLEX, 24) {

	override val configKeyDomain = ConfigKey.Domain(
		"beta.mangaplex.com",
		"mangaplex.com",
	)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		// Images are served from mangaplex.com / tapas.io — refer as the WP site.
		.set("Referer", "https://mangaplex.com/")
		.build()

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val host = request.url.host.lowercase(Locale.ROOT)
		val builder = request.newBuilder()
		when {
			// Prefer self-hosted mangaplex images; set WP referer for hotlink-friendly hosts.
			host.contains("mangaplex.com") || host.contains("wp.com") -> {
				builder.header("Referer", "https://mangaplex.com/")
				builder.header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
			}
			// Tapas Akamai often blocks scrapers; omit referer (browser-like bare GET).
			host.contains("tapas.io") -> {
				builder.removeHeader("Referer")
				builder.header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
				builder.header("Sec-Fetch-Dest", "image")
				builder.header("Sec-Fetch-Mode", "no-cors")
				builder.header("Sec-Fetch-Site", "cross-site")
			}
		}
		// Encode spaces in path (covers like "The Ghost Doctor Cover Image.jpg").
		val fixedUrl = request.url.toString()
			.replace(" ", "%20")
			.toHttpUrlOrNull()
		if (fixedUrl != null && fixedUrl != request.url) {
			builder.url(fixedUrl)
		}
		return chain.proceed(builder.build())
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = DEFAULT_TAGS.mapToSet { (key, title) ->
			MangaTag(key = key, title = title, source = source)
		},
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		// Library is small and mostly single-page; avoid N sequential detail fetches.
		if (page > 1) return emptyList()

		val query = filter.query?.trim().orEmpty()
		val list = when {
			query.isNotEmpty() -> {
				parseMangaCards(
					webClient.httpGet("https://$domain/search?q=${query.urlEncoded()}").parseHtml(),
				)
			}
			filter.tags.isNotEmpty() -> {
				val primary = filter.tags.first()
				parseMangaCards(
					webClient.httpGet("https://$domain/genre/${primary.key}").parseHtml(),
				)
			}
			else -> loadCatalogFast()
		}
		val filtered = applyLocalFilters(list, filter)
		return when (order) {
			SortOrder.ALPHABETICAL -> filtered.sortedBy { it.title.lowercase(Locale.ROOT) }
			else -> filtered
		}
	}

	/** Parallel genre + search scrapes → one fast catalog page. */
	private suspend fun loadCatalogFast(): List<Manga> = coroutineScope {
		val jobs = buildList {
			add(async {
				parseMangaCards(
					webClient.httpGet("https://$domain/search?q=").parseHtml(),
				)
			})
			for ((key, _) in DEFAULT_TAGS.take(8)) {
				add(async {
					runCatching {
						parseMangaCards(
							webClient.httpGet("https://$domain/genre/$key").parseHtml(),
						)
					}.getOrDefault(emptyList())
				})
			}
		}
		jobs.awaitAll().flatten().distinctBy { it.url }
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val id = extractMangaId(manga.url)
		val doc = webClient.httpGet("https://$domain/manga/$id").parseHtml()
		val parsed = parseMangaDetails(doc)
		val chapters = fetchAllChapters(id)
		val adult = parsed.tags.any {
			it.key in setOf("mature", "ecchi", "hentai", "smut")
		}
		return manga.copy(
			title = parsed.title ?: manga.title,
			altTitles = parsed.altTitles.ifEmpty { manga.altTitles },
			coverUrl = parsed.cover ?: manga.coverUrl,
			largeCoverUrl = parsed.cover ?: manga.largeCoverUrl,
			authors = parsed.authors.ifEmpty { manga.authors },
			tags = parsed.tags.ifEmpty { manga.tags },
			description = parsed.description ?: manga.description,
			state = MangaState.ONGOING,
			contentRating = if (adult) ContentRating.ADULT else ContentRating.SAFE,
			chapters = chapters,
			publicUrl = "https://$domain/manga/$id",
			url = "/manga/$id",
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = when {
			chapter.url.startsWith("http://") || chapter.url.startsWith("https://") -> chapter.url
			else -> chapter.url.toAbsoluteUrl(domain)
		}
		// Always load chapter HTML from the WordPress host (not beta).
		val wpUrl = fullUrl
			.replace("https://beta.mangaplex.com", "https://mangaplex.com")
			.replace("http://beta.mangaplex.com", "https://mangaplex.com")

		// Soft GET — webClient.httpGet throws on 404/410 which many API links are.
		val (code, html) = softGetHtml(wpUrl)
		var pages = if (code in 200..299) extractPagesFromHtml(html) else emptyList()

		// Dead WP link: try site search for this chapter slug/title.
		if (pages.isEmpty()) {
			val slug = wpUrl.trimEnd('/').substringAfterLast('/')
			val recovered = recoverChapterHtml(slug, chapter)
			if (recovered != null) {
				pages = extractPagesFromHtml(recovered)
			}
		}

		if (pages.isEmpty()) {
			throw ParseException(
				when {
					code == 404 || code == 410 ->
						"Chapter page was removed from MangaPlex (HTTP $code). Try another chapter."
					else ->
						"No readable pages found. Some MangaPlex chapters use blocked Tapas CDN images or dead links."
				},
				wpUrl,
			)
		}
		return pages.map { url ->
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	/**
	 * Soft HTML fetch that does **not** throw on 4xx (unlike [webClient.httpGet]).
	 */
	private suspend fun softGetHtml(url: String): Pair<Int, String> {
		val request = Request.Builder()
			.url(url)
			.get()
			.headers(getRequestHeaders())
			.tag(MangaSource::class.java, source)
			.build()
		val response = context.httpClient.newCall(request).await()
		return response.use { resp ->
			resp.code to (resp.body?.string().orEmpty())
		}
	}

	private fun extractPagesFromHtml(html: String): List<String> {
		if (html.isEmpty()) return emptyList()
		val doc = Jsoup.parse(html)
		// Prefer real post body; fall back to article/body.
		val roots = listOfNotNull(
			doc.selectFirst("div.entry-content"),
			doc.selectFirst("div.post-content"),
			doc.selectFirst("article .entry-content"),
			doc.selectFirst("article"),
			doc.body(),
		)
		for (root in roots) {
			val urls = extractImagesFrom(root)
			// Real chapters usually have several sequential pages; ignore sidebar junk.
			if (urls.size >= 2 || urls.any { it.contains("/manga/") || it.contains("tapas.io") }) {
				return urls
			}
		}
		return emptyList()
	}

	private fun extractImagesFrom(root: Element): List<String> {
		val urls = LinkedHashSet<String>()
		for (img in root.select("img")) {
			val raw = sequenceOf(
				"data-src",
				"data-lazy-src",
				"data-original",
				"data-lazy-srcset",
				"srcset",
				"src",
			).map { name ->
				val v = img.attr(name).trim()
				// srcset: "url 1x, url2 2x" → first URL
				if (name.contains("srcset") && v.isNotEmpty()) {
					v.split(',').firstOrNull()?.trim()?.substringBefore(' ')?.trim().orEmpty()
				} else {
					v
				}
			}.firstOrNull { it.isNotEmpty() && !it.startsWith("data:") } ?: continue
			if (isJunkImage(raw)) continue
			urls += normalizeImageUrl(raw)
		}
		if (urls.size < 2) {
			PAGE_URL_REGEX.findAll(root.html()).forEach { m ->
				val u = m.value.trim()
				if (!isJunkImage(u)) urls += normalizeImageUrl(u)
			}
		}
		// Prefer self-hosted mangaplex pages over random site chrome.
		val hosted = urls.filter {
			it.contains("/manga/") ||
				it.contains("tapas.io") ||
				it.contains("wp.com")
		}
		return (hosted.ifEmpty { urls }).toList()
	}

	/** Search mangaplex.com for a live post when the API link is 404/410. */
	private suspend fun recoverChapterHtml(slug: String, chapter: MangaChapter): String? {
		val query = buildString {
			append(slug.replace('-', ' '))
			if (chapter.number > 0f) {
				append(' ')
				append(chapter.number.toInt())
			}
		}.urlEncoded()
		val searchHtml = runCatching {
			softGetHtml("https://mangaplex.com/?s=$query").second
		}.getOrNull() ?: return null
		val searchDoc = Jsoup.parse(searchHtml)
		val candidates = searchDoc.select("article a[href], h2 a[href]")
			.map { it.attr("href").trim() }
			.filter { it.contains("mangaplex.com") && it.contains("chapter") }
			.distinct()
			.take(5)
		for (href in candidates) {
			val (code, body) = softGetHtml(href)
			if (code in 200..299 && extractPagesFromHtml(body).size >= 2) {
				return body
			}
		}
		return null
	}

	private fun parseMangaCards(doc: Document): List<Manga> {
		return doc.select("div.card.e-card a[href*=manga/]").mapNotNull { a ->
			val href = a.attr("href").trim()
			val id = Regex("""manga/(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
				?: return@mapNotNull null
			val title = a.selectFirst("p.card-text.text-gray-900, p.card-text")?.text()?.trim()
				?: a.selectFirst("img.card-img-top")?.attr("alt")?.trim()
				?: return@mapNotNull null
			if (title.isEmpty() || title.equals("Title(s)", ignoreCase = true)) return@mapNotNull null
			val cover = a.selectFirst("img")?.let { img ->
				sequenceOf("data-src", "src").map { img.attr(it).trim() }
					.firstOrNull { it.isNotEmpty() && !it.startsWith("data:") }
			}?.let { absCover(it) }
			Manga(
				id = generateUid("/manga/$id"),
				url = "/manga/$id",
				publicUrl = "https://$domain/manga/$id",
				title = title,
				coverUrl = cover,
				largeCoverUrl = cover,
				altTitles = emptySet(),
				authors = emptySet(),
				tags = emptySet(),
				rating = RATING_UNKNOWN,
				state = MangaState.ONGOING,
				contentRating = ContentRating.SAFE,
				source = source,
				description = null,
			)
		}.distinctBy { it.url }
	}

	private data class ParsedDetails(
		val title: String?,
		val altTitles: Set<String>,
		val authors: Set<String>,
		val tags: Set<MangaTag>,
		val cover: String?,
		val description: String?,
	)

	private fun parseMangaDetails(doc: Document): ParsedDetails {
		val lines = doc.body().html()
			.replace(Regex("""<(script|style)[^>]*>[\s\S]*?</\1>""", RegexOption.IGNORE_CASE), " ")
			.replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
			.replace(Regex("""</(?:p|div|h[1-6]|li|tr)>""", RegexOption.IGNORE_CASE), "\n")
			.replace(Regex("""<[^>]+>"""), "\n")
			.replace("&nbsp;", " ")
			.replace("&amp;", "&")
			.split('\n')
			.map { it.replace(Regex("\\s+"), " ").trim() }
			.filter { it.isNotEmpty() }

		fun valueAfter(label: Regex): String? {
			val idx = lines.indexOfFirst { label.matches(it) }
			if (idx < 0 || idx + 1 >= lines.size) return null
			return lines[idx + 1].nullIfEmpty()
		}

		val title = valueAfter(Regex("""(?i)title\(s\)"""))
		val author = valueAfter(Regex("""(?i)author\(s\)"""))
		val artist = valueAfter(Regex("""(?i)artist"""))

		val altTitles = buildSet {
			val tIdx = lines.indexOfFirst { it.equals("Title(s)", ignoreCase = true) }
			val aIdx = lines.indexOfFirst { it.equals("Author(s)", ignoreCase = true) }
			if (tIdx >= 0 && aIdx > tIdx + 1) {
				for (i in (tIdx + 2) until aIdx) {
					lines[i].split(',')
						.mapNotNull { it.trim().nullIfEmpty() }
						.filter { it != title }
						.forEach { add(it) }
				}
			}
		}

		val genreLine = lines.firstOrNull { line ->
			line.contains(" / ") &&
				line.length < 220 &&
				Regex("""(?i)(action|drama|fantasy|romance|comedy|manhwa|webtoon|adventure)""")
					.containsMatchIn(line)
		}
		val tags = genreLine
			?.split('/')
			?.mapNotNull { g ->
				val name = g.trim()
				if (name.isEmpty() || name.all { it.isDigit() }) return@mapNotNull null
				MangaTag(
					key = slugify(name),
					title = name.toTitleCase(sourceLocale),
					source = source,
				)
			}
			?.toSet()
			.orEmpty()

		val cover = doc.select("img").mapNotNull { img ->
			sequenceOf("data-src", "src").map { img.attr(it).trim() }
				.firstOrNull { it.isNotEmpty() && !it.startsWith("data:") }
		}.firstOrNull { u ->
			!isJunkImage(u) &&
				(u.contains("cover", ignoreCase = true) ||
					(u.contains("/img/", ignoreCase = true) && !u.contains("banner", ignoreCase = true)))
		}?.let { absCover(it) }
			?: doc.select("img").mapNotNull { img ->
				sequenceOf("data-src", "src").map { img.attr(it).trim() }
					.firstOrNull { it.isNotEmpty() && !it.startsWith("data:") }
			}.firstOrNull { u ->
				!isJunkImage(u) && u.contains("/img/", ignoreCase = true)
			}?.let { absCover(it) }

		// Real synopsis lives in the Summary tab (#home), as <br>-separated text.
		val description = extractDescription(doc)

		return ParsedDetails(
			title = title,
			altTitles = altTitles,
			authors = setOfNotNull(author, artist),
			tags = tags,
			cover = cover,
			description = description,
		)
	}

	private fun extractDescription(doc: Document): String? {
		// Tab pane with Summary content
		val tab = doc.selectFirst("#home.tab-pane, #home, div.tab-pane.show.active, div.tab-pane.active")
		if (tab != null) {
			val html = tab.html()
				.replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
				.replace(Regex("""</p>""", RegexOption.IGNORE_CASE), "\n")
			val text = Jsoup.parseBodyFragment(html).text()
				.replace(Regex("[ \\t]+"), " ")
				.replace(Regex(" *\\n *"), "\n")
				.replace(Regex("\\n{3,}"), "\n\n")
				.trim()
			if (text.length >= 40 && !isSeoDescription(text)) return text
		}
		// Fallback paragraphs
		return doc.select("p").map { it.text().trim() }
			.firstOrNull { p ->
				p.length >= 80 &&
					!p.contains(" / ") &&
					!isSeoDescription(p) &&
					!p.contains("Publisher", ignoreCase = true) &&
					!p.contains("Author", ignoreCase = true)
			}
	}

	private suspend fun fetchAllChapters(mangaId: Int): List<MangaChapter> = coroutineScope {
		val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)
		// Fetch first page, then remaining pages in parallel based on size.
		val first = runCatching {
			webClient.httpGet(
				"https://$domain/api/get_chapters.php?id=$mangaId&page=1",
			).parseJsonArray()
		}.getOrNull() ?: return@coroutineScope emptyList()

		val pageSizeHint = first.length().coerceAtLeast(1)
		val morePages = if (first.length() >= 20) {
			// Heuristic: load a few more pages in parallel
			(2..8).map { p ->
				async {
					runCatching {
						webClient.httpGet(
							"https://$domain/api/get_chapters.php?id=$mangaId&page=$p",
						).parseJsonArray()
					}.getOrNull()
				}
			}.awaitAll().filterNotNull().filter { it.length() > 0 }
		} else {
			emptyList()
		}

		val collected = ArrayList<Triple<String, Float, Long>>()
		for (arr in listOf(first) + morePages) {
			for (i in 0 until arr.length()) {
				val obj = arr.optJSONObject(i) ?: continue
				val link = obj.optString("link").nullIfEmpty() ?: continue
				val number = obj.optDouble("number", Double.NaN).toFloat()
				if (number.isNaN()) continue
				val created = obj.optString("created").nullIfEmpty()
				val date = created?.let {
					runCatching { dateFormat.parse(it)?.time }.getOrNull()
				} ?: 0L
				val path = if (link.startsWith("http")) {
					link
				} else {
					link.toAbsoluteUrl("mangaplex.com")
				}
				collected += Triple(path, number, date)
			}
		}
		// If first page was full and we might have more, sequentially fill gaps
		if (first.length() >= 20 && morePages.isNotEmpty() && morePages.last().length() >= 20) {
			var page = 9
			while (page <= 40) {
				val arr = runCatching {
					webClient.httpGet(
						"https://$domain/api/get_chapters.php?id=$mangaId&page=$page",
					).parseJsonArray()
				}.getOrNull() ?: break
				if (arr.length() == 0) break
				for (i in 0 until arr.length()) {
					val obj = arr.optJSONObject(i) ?: continue
					val link = obj.optString("link").nullIfEmpty() ?: continue
					val number = obj.optDouble("number", Double.NaN).toFloat()
					if (number.isNaN()) continue
					val created = obj.optString("created").nullIfEmpty()
					val date = created?.let {
						runCatching { dateFormat.parse(it)?.time }.getOrNull()
					} ?: 0L
					val path = if (link.startsWith("http")) link else link.toAbsoluteUrl("mangaplex.com")
					collected += Triple(path, number, date)
				}
				if (arr.length() < 20) break
				page++
			}
		}
		@Suppress("UNUSED_VARIABLE")
		val unused = pageSizeHint

		val sorted = collected.distinctBy { it.first }.sortedBy { it.second }
		sorted.mapChapters(reversed = true) { _, (path, number, date) ->
			MangaChapter(
				id = generateUid(path),
				title = "Chapter ${number.toString().removeSuffix(".0")}",
				number = number,
				volume = 0,
				url = path,
				scanlator = null,
				uploadDate = date,
				branch = null,
				source = source,
			)
		}
	}

	private fun applyLocalFilters(list: List<Manga>, filter: MangaListFilter): List<Manga> {
		if (filter.tags.isEmpty() && filter.tagsExclude.isEmpty()) return list
		val includeKeys = filter.tags.mapToSet { it.key.lowercase(Locale.ROOT) }
		val excludeKeys = filter.tagsExclude.mapToSet { it.key.lowercase(Locale.ROOT) }
		val extraInclude = if (includeKeys.size <= 1) emptySet() else includeKeys
		return list.filter { manga ->
			val keys = manga.tags.mapToSet { it.key.lowercase(Locale.ROOT) }
			(extraInclude.isEmpty() || extraInclude.all { k ->
				k in keys || manga.tags.any { slugify(it.title) == k }
			}) && (excludeKeys.isEmpty() || keys.none { it in excludeKeys })
		}
	}

	private fun extractMangaId(url: String): Int {
		return Regex("""manga/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()
			?: url.trim('/').substringAfterLast('/').toIntOrNull()
			?: throw ParseException("Invalid manga url: $url", url)
	}

	private fun absCover(raw: String): String {
		val t = raw.trim().replace(" ", "%20")
		return when {
			t.startsWith("http://") || t.startsWith("https://") -> t
			t.startsWith("//") -> "https:$t"
			t.startsWith('/') -> "https://$domain$t"
			else -> "https://$domain/$t"
		}
	}

	private fun normalizeImageUrl(raw: String): String {
		val t = raw.trim()
		val absolute = when {
			t.startsWith("http://") || t.startsWith("https://") -> t
			t.startsWith("//") -> "https:$t"
			t.startsWith('/') -> "https://mangaplex.com$t"
			else -> "https://mangaplex.com/$t"
		}
		// Keep path spaces as %20 for Coil/OkHttp
		return absolute.replace(" ", "%20")
	}

	private fun slugify(name: String): String =
		name.lowercase(Locale.ROOT)
			.replace(Regex("[^a-z0-9]+"), "-")
			.trim('-')

	private fun isJunkImage(url: String): Boolean {
		val lower = url.lowercase(Locale.ROOT)
		return lower.contains("gravatar") ||
			lower.contains("avatar") ||
			lower.contains("emoji") ||
			lower.contains("logo") ||
			lower.contains("mangaplex.png") ||
			lower.contains("mangaplex-icon") ||
			lower.contains("patreon") ||
			lower.contains("/flag/") ||
			lower.contains("banner-200") ||
			lower.contains("banner-520") ||
			lower.contains("cover-e16") || // site-wide junk covers in sidebars
			lower.endsWith("/cover.png") ||
			lower.endsWith("/cover.jpg") ||
			lower.contains("treasured-sakura") ||
			lower.contains("tian-lun-chapter") ||
			lower.endsWith(".svg")
	}

	private fun isSeoDescription(text: String): Boolean {
		val lower = text.lowercase(Locale.ROOT)
		return lower.contains("in english online free") ||
			lower.contains("mangaplex") ||
			(lower.startsWith("read ") && lower.contains("manga"))
	}

	private companion object {
		val PAGE_URL_REGEX = Regex(
			"""https?://[^\s"'<>]+\.(?:jpg|jpeg|png|webp)""",
			RegexOption.IGNORE_CASE,
		)

		val DEFAULT_TAGS = listOf(
			"action" to "Action",
			"adventure" to "Adventure",
			"comedy" to "Comedy",
			"drama" to "Drama",
			"ecchi" to "Ecchi",
			"fantasy" to "Fantasy",
			"harem" to "Harem",
			"historical" to "Historical",
			"horror" to "Horror",
			"isekai" to "Isekai",
			"magic" to "Magic",
			"manhwa" to "Manhwa",
			"martial-arts" to "Martial Arts",
			"mature" to "Mature",
			"medical" to "Medical",
			"military" to "Military",
			"mystery" to "Mystery",
			"romance" to "Romance",
			"school-life" to "School Life",
			"sci-fi" to "Sci-Fi",
			"slice-of-life" to "Slice of Life",
			"supernatural" to "Supernatural",
			"survival" to "Survival",
			"system" to "System",
			"time-travel" to "Time Travel",
			"webtoon" to "Webtoon",
			"wuxia" to "Wuxia",
		)
	}
}
