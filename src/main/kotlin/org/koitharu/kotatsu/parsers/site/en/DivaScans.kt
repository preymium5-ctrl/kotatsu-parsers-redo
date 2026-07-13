package org.koitharu.kotatsu.parsers.site.en

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.toJSONArrayOrNull
import org.koitharu.kotatsu.parsers.util.json.toJSONObjectOrNull
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

@MangaSourceParser("DIVASCANS", "DivaScans", "en", ContentType.HENTAI)
internal class DivaScans(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.DIVASCANS, 24) {

    override val configKeyDomain = ConfigKey.Domain("divascans.org")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
        keys.add(ConfigKey.InterceptCloudflare(defaultValue = true))
    }

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .set("Referer", "https://$domain/")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
        SortOrder.POPULARITY,
        SortOrder.RATING
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            // API only accepts a single genre query; extra tags are AND-filtered client-side.
            isMultipleTagsSupported = true,
            // API has no reliable exclude-genre param — applied client-side after fetch.
            isTagsExclusionSupported = true,
        )

    private var availableTagsCache: Set<MangaTag>? = null

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        var tags = availableTagsCache
        if (tags == null) {
            val list = mutableListOf<MangaTag>()
            try {
                val genreJson = webClient.httpGet("https://$domain/api/genres").body?.string()?.toJSONObjectOrNull()
                val genreArray = genreJson?.optJSONArray("genres")
                if (genreArray != null) {
                    for (i in 0 until genreArray.length()) {
                        val obj = genreArray.getJSONObject(i)
                        val slug = obj.optString("slug")
                        val name = obj.optString("name")
                        if (slug.isNotEmpty() && name.isNotEmpty()) {
                            list.add(MangaTag(key = slug, title = name, source = source))
                        }
                    }
                }
                tags = list.toSet()
                if (tags.isNotEmpty()) {
                    availableTagsCache = tags
                }
            } catch (e: Exception) {
                tags = emptySet()
            }
        }
        
        return MangaListFilterOptions(
            availableTags = tags ?: emptySet(),
            availableStates = EnumSet.of(
                MangaState.ONGOING,
                MangaState.FINISHED,
                MangaState.PAUSED,
                MangaState.ABANDONED
            ),
            availableContentTypes = EnumSet.of(
                ContentType.MANHWA,
                ContentType.MANHUA,
                ContentType.MANGA
            ),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val isSearch = !filter.query.isNullOrBlank()
        val url = if (isSearch) {
            "https://$domain/api/search".toHttpUrl().newBuilder().apply {
                addQueryParameter("q", filter.query)
                addQueryParameter("page", page.toString())
            }.build()
        } else {
            "https://$domain/api/series".toHttpUrl().newBuilder().apply {
                addQueryParameter("page", page.toString())
                addQueryParameter("limit", pageSize.toString())
                val sortStr = when (order) {
                    SortOrder.NEWEST -> "latest"
                    SortOrder.ALPHABETICAL -> "az"
                    SortOrder.POPULARITY -> "popular"
                    SortOrder.RATING -> "rating"
                    else -> ""
                }
                if (sortStr.isNotEmpty()) {
                    addQueryParameter("sort", sortStr)
                }
                // Server supports one genre param; remaining tags filtered in applyLocalFilters.
                filter.tags.firstOrNull()?.let { tag ->
                    addQueryParameter("genre", tag.key)
                }

                if (filter.states.isNotEmpty()) {
                    val statesStr = filter.states.mapNotNull { state ->
                        when (state) {
                            MangaState.ONGOING -> "ONGOING"
                            MangaState.FINISHED -> "COMPLETED"
                            MangaState.ABANDONED -> "DROPPED"
                            MangaState.PAUSED -> "HIATUS"
                            else -> null
                        }
                    }.joinToString(",")
                    if (statesStr.isNotEmpty()) addQueryParameter("status", statesStr)
                }
                
                if (filter.types.isNotEmpty()) {
                    val typesStr = filter.types.mapNotNull { type ->
                        when (type) {
                            ContentType.MANHWA -> "MANHWA"
                            ContentType.MANHUA -> "MANHUA"
                            ContentType.MANGA -> "MANGA"
                            else -> null
                        }
                    }.joinToString(",")
                    if (typesStr.isNotEmpty()) addQueryParameter("type", typesStr)
                }
            }.build()
        }

        val jsonStr = webClient.httpGet(url).body?.string() ?: return emptyList()
        val json = jsonStr.toJSONObjectOrNull()
        
        val dataArray = if (isSearch) json?.optJSONArray("series") else json?.optJSONArray("data")
        if (dataArray == null) return emptyList()

        val list = mutableListOf<Manga>()
        for (i in 0 until dataArray.length()) {
            val obj = dataArray.getJSONObject(i)
            val slug = obj.optString("urlSlug", "").ifEmpty { obj.optString("slug", "") }
            val title = obj.optString("title", "").ifEmpty { obj.optString("name", "") }
            val cover = obj.optString("coverImage", "").ifEmpty { obj.optString("thumbnail", "") }
            val statusStr = obj.optString("status", "")
            
            if (slug.isEmpty() || title.isEmpty()) continue
            
            val type = obj.optString("type", "comic").lowercase()
            val urlType = if (type.contains("novel")) "novel" else "comic"
            
            val state = when (statusStr.uppercase(Locale.ROOT)) {
                "ONGOING" -> MangaState.ONGOING
                "COMPLETED", "FINISHED" -> MangaState.FINISHED
                "DROPPED", "CANCELLED" -> MangaState.ABANDONED
                "HIATUS", "ON_HIATUS" -> MangaState.PAUSED
                "COMING_SOON", "UPCOMING" -> MangaState.UPCOMING
                else -> null
            }
            
            val isMature = obj.optBoolean("isMature", false)

            val tags = parseTagsFromSeries(obj)

            list.add(
                Manga(
                    id = generateUid(slug),
                    url = "/series/$urlType/$slug",
                    publicUrl = "https://$domain/series/$urlType/$slug",
                    coverUrl = if (cover.startsWith("http")) cover else "https://media.divascans.org${cover.removePrefix("/uploads")}",
                    title = title,
                    altTitles = emptySet<String>(),
                    rating = RATING_UNKNOWN,
                    contentRating = if (isMature) ContentRating.ADULT else ContentRating.SAFE,
                    tags = tags,
                    state = state,
                    authors = emptySet<String>(),
                    source = source,
                )
            )
        }
        return applyLocalFilters(list.distinctBy { it.url }, filter)
    }

    /**
     * Client-side filters the API does not fully honour:
     * - multiple include tags (AND — series must have every selected genre)
     * - exclude tags
     */
    private fun applyLocalFilters(list: List<Manga>, filter: MangaListFilter): List<Manga> {
        if (filter.tags.isEmpty() && filter.tagsExclude.isEmpty()) return list
        val includeKeys = filter.tags.mapToSet { it.key }
        val excludeKeys = filter.tagsExclude.mapToSet { it.key }
        return list.filter { manga ->
            val mangaKeys = manga.tags.mapToSet { it.key }
            (includeKeys.isEmpty() || includeKeys.all { it in mangaKeys }) &&
                (excludeKeys.isEmpty() || mangaKeys.none { it in excludeKeys })
        }
    }

    private fun parseTagsFromSeries(obj: JSONObject): Set<MangaTag> {
        val genres = obj.optJSONArray("genres") ?: return emptySet()
        val tags = mutableSetOf<MangaTag>()
        for (i in 0 until genres.length()) {
            val g = genres.optJSONObject(i) ?: continue
            val genreObj = g.optJSONObject("genre") ?: g
            val key = genreObj.optString("slug").ifEmpty { genreObj.optString("name") }
            val name = genreObj.optString("name").ifEmpty { key }
            if (key.isNotEmpty() && name.isNotEmpty()) {
                tags.add(MangaTag(key = key, title = name, source = source))
            }
        }
        return tags
    }

    private fun extractRscData(doc: org.jsoup.nodes.Document): String {
        val scripts = doc.select("script")
        val sbRsc = java.lang.StringBuilder()
        for (script in scripts) {
            val scriptContent = script.data()
            var index = scriptContent.indexOf("self.__next_f.push(")
            while (index != -1) {
                val start = scriptContent.indexOf("[", index)
                if (start == -1) break
                
                val nextPush = scriptContent.indexOf("self.__next_f.push(", start)
                var chunkStr = if (nextPush != -1) {
                    scriptContent.substring(start, nextPush).trim()
                } else {
                    scriptContent.substring(start).trim()
                }
                
                chunkStr = chunkStr.removeSuffix(";").trim().removeSuffix(")").trim()
                
                val jsonArray = chunkStr.toJSONArrayOrNull()
                if (jsonArray != null && jsonArray.length() >= 2) {
                    val strValue = jsonArray.optString(1, "")
                    if (strValue.isNotEmpty()) {
                        sbRsc.append(strValue)
                    }
                }
                index = nextPush
            }
        }
        return sbRsc.toString()
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val absoluteUrl = manga.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(absoluteUrl).parseHtml()
        
        var description = ""
        var authors = setOf<String>()
        var tags = setOf<MangaTag>()
        
        val fullData = extractRscData(doc)
        var text = fullData
        repeat(3) {
            text = text.replace("\\\"", "\"").replace("\\\\", "\\")
        }

        val chapters = mutableListOf<MangaChapter>()
        val regex = Regex(
            """"id":"(cm[a-z0-9]+)","number":(\d+(?:\.\d+)?),"title":"([^"]*)","coverImage":(?:null|"[^"]*"),"isLocked":(true|false),"coinPrice":(\d+),"viewCount":(\d+),"likeCount":(\d+),"publishedAt":"([^"]+)""""
        )
        
        for (match in regex.findAll(text)) {
            val id = match.groupValues[1]
            val numberStr = match.groupValues[2]
            val title = match.groupValues[3].ifEmpty { "Chapter $numberStr" }
            val isLocked = match.groupValues[4] == "true"
            val publishedAt = match.groupValues[8]
            
            if (isLocked) continue
            
            val number = numberStr.toFloatOrNull() ?: continue
            val chapterUrl = "${manga.url}/chapter/${numberStr.replace(".0", "")}"
            
            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val uploadDate = runCatching { dateFormat.parse(publishedAt.substringBefore("."))?.time ?: 0L }.getOrDefault(0L)
            
            chapters.add(
                MangaChapter(
                    id = generateUid(chapterUrl),
                    title = title.replace("\\u0026", "&"),
                    number = number,
                    volume = 0,
                    url = chapterUrl,
                    uploadDate = uploadDate,
                    source = source,
                    scanlator = null,
                    branch = null,
                )
            )
        }
        
        val descMatch = Regex("""\"description\":\"([^"]+)\"""").find(fullData)
        if (descMatch != null) {
            description = descMatch.groupValues[1].replace("\\n", "\n").replace("\\u003c", "<").replace("\\u003e", ">")
            description = org.jsoup.Jsoup.parseBodyFragment(description).text()
        }
        
        val genresRegex = Regex("""\"genres\":\[(.*?)\]""").find(fullData)
        if (genresRegex != null) {
            val genreNames = Regex("""\"name\":\"([^"]+)\"""").findAll(genresRegex.groupValues[1])
            tags = genreNames.map { MangaTag(key = it.groupValues[1], title = it.groupValues[1], source = source) }.toSet()
        }

        return manga.copy(
            description = description,
            authors = authors,
            tags = tags,
            chapters = chapters.sortedByDescending { it.number }
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val absoluteUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(absoluteUrl).parseHtml()
        
        var fullData = extractRscData(doc)
        repeat(3) {
            fullData = fullData.replace("\\\"", "\"").replace("\\\\", "\\")
        }
        
        var bestPagesBlock = ""
        var maxImages = 0
        var pagesStartIndex = fullData.indexOf("\"pages\":[")
        while (pagesStartIndex != -1) {
            val arrayStart = fullData.indexOf("[", pagesStartIndex)
            if (arrayStart != -1) {
                var bracketCount = 0
                var arrayEnd = -1
                for (i in arrayStart until fullData.length) {
                    if (fullData[i] == '[') bracketCount++
                    else if (fullData[i] == ']') bracketCount--
                    
                    if (bracketCount == 0) {
                        arrayEnd = i
                        break
                    }
                }
                
                if (arrayEnd != -1) {
                    val block = fullData.substring(arrayStart, arrayEnd + 1)
                    val count = Regex("""\"imageUrl\":\"([^\"]+)\"""").findAll(block).count()
                    if (count > maxImages) {
                        maxImages = count
                        bestPagesBlock = block
                    }
                }
            }
            pagesStartIndex = fullData.indexOf("\"pages\":[", pagesStartIndex + 1)
        }
        
        var urls = mutableListOf<String>()
        if (bestPagesBlock.isNotEmpty()) {
            val imageMatches = Regex("""\"imageUrl\":\"([^\"]+)\"""").findAll(bestPagesBlock)
            val jsonUrls = imageMatches.map { match ->
                var url = match.groupValues[1].replace("\\u0026", "&")
                if (url.contains("url=")) {
                    url = url.substringAfter("url=").substringBefore("&")
                    url = java.net.URLDecoder.decode(url, "UTF-8")
                }
                if (url.startsWith("/")) url = "https://media.divascans.org$url"
                url.replace("divascans.org", "media.divascans.org")
                   .replace("media.media.divascans.org", "media.divascans.org")
                   .replace("/_next/image", "")
                   .replace("/uploads/", "/")
                   .substringBefore("?")
            }.distinct()
            .filter { !it.substringAfterLast("/").startsWith("s-") }
            .toList()
            urls.addAll(jsonUrls)
        }
        
        if (urls.isEmpty()) {
            val domImages = doc.select("div.reader-images img, div.chapter-container img, main img[src*='chapter']")
            for (img in domImages) {
                var imgUrl = img.absUrl("data-src").ifEmpty { img.absUrl("src") }
                if (imgUrl.isNotEmpty() && !imgUrl.startsWith("data:")) {
                    if (imgUrl.contains("url=")) {
                        imgUrl = imgUrl.substringAfter("url=").substringBefore("&")
                        imgUrl = java.net.URLDecoder.decode(imgUrl, "UTF-8")
                    }
                    if (imgUrl.startsWith("/")) imgUrl = "https://media.divascans.org$imgUrl"
                    imgUrl = imgUrl.replace("divascans.org", "media.divascans.org")
                       .replace("media.media.divascans.org", "media.divascans.org")
                       .replace("/_next/image", "")
                       .replace("/uploads/", "/")
                       .substringBefore("?")
                    urls.add(imgUrl)
                }
            }
            urls = urls.distinct().toMutableList()
        }
        
        if (urls.isEmpty() && fullData.contains("\"isLocked\":true")) {
            throw ParseException("Chapter is locked on DivaScans", absoluteUrl)
        }
        
        if (urls.isEmpty()) {
            throw ParseException("No pages found", absoluteUrl)
        }
        
        return urls.map { url ->
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source
            )
        }
    }
}
