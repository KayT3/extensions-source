package eu.kanade.tachiyomi.extension.all.nhentai

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.randomua.addRandomUAPreferenceToScreen
import keiyoushi.lib.randomua.getPrefCustomUA
import keiyoushi.lib.randomua.getPrefUAType
import keiyoushi.lib.randomua.setRandomUserAgent
import keiyoushi.utils.getPreferencesLazy
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

open class NHentai(
    override val lang: String,
    private val nhLang: String,
) : ParsedHttpSource(),
    ConfigurableSource {

    final override val baseUrl = "https://nhentai.net"

    override val id by lazy { if (lang == "all") 7309872737163460316 else super.id }

    override val name = "NHentai"

    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    override val client: OkHttpClient by lazy {
        network.cloudflareClient.newBuilder()
            .setRandomUserAgent(
                userAgentType = preferences.getPrefUAType(),
                customUA = preferences.getPrefCustomUA(),
                filterInclude = listOf("chrome"),
            )
            .rateLimit(4)
            .build()
    }

    private var displayFullTitle: Boolean = when (preferences.getString(TITLE_PREF, "full")) {
        "full" -> true
        else -> false
    }

    private val shortenTitleRegex = Regex("""(\[[^]]*]|[({][^)}]*[)}])""")

    // Two formats: most galleries include milliseconds, some don't
    private val dateFormats = listOf(
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") },
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") },
    )

    private fun parseDatetime(dateStr: String): Long {
        for (fmt in dateFormats) {
            runCatching { fmt.parse(dateStr)?.time }.getOrNull()?.let { return it }
        }
        return 0L
    }

    private fun String.shortenTitle() = replace(shortenTitleRegex, "").trim()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = TITLE_PREF
            title = TITLE_PREF
            entries = arrayOf("Full Title", "Short Title")
            entryValues = arrayOf("full", "short")
            summary = "%s"
            setDefaultValue("full")

            setOnPreferenceChangeListener { _, newValue ->
                displayFullTitle = when (newValue) {
                    "full" -> true
                    else -> false
                }
                true
            }
        }.also(screen::addPreference)

        addRandomUAPreferenceToScreen(screen)
    }

    // ── Listing ──────────────────────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int) =
        GET(
            if (nhLang.isBlank()) "$baseUrl/?page=$page"
            else "$baseUrl/language/$nhLang/?page=$page",
            headers,
        )

    // Site rebuilt in SvelteKit; gallery cards are now <a class="cover" href="/g/…">
    override fun latestUpdatesSelector() = "a.cover[href^=\"/g/\"]"

    override fun latestUpdatesFromElement(element: Element) = SManga.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.selectFirst(".caption")?.text()?.trim()?.let {
            if (displayFullTitle) it else it.shortenTitle()
        } ?: ""
        thumbnail_url = element.selectFirst("img")?.attr("src")
    }

    override fun latestUpdatesNextPageSelector() = "a.next"

    override fun popularMangaRequest(page: Int) =
        GET(
            if (nhLang.isBlank()) "$baseUrl/search/?q=\"\"&sort=popular&page=$page"
            else "$baseUrl/language/$nhLang/popular?page=$page",
            headers,
        )

    override fun popularMangaFromElement(element: Element) = latestUpdatesFromElement(element)

    override fun popularMangaSelector() = latestUpdatesSelector()

    override fun popularMangaNextPageSelector() = latestUpdatesNextPageSelector()

    // ── Search ───────────────────────────────────────────────────────────────

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = when {
        query.startsWith(PREFIX_ID_SEARCH) -> {
            val id = query.removePrefix(PREFIX_ID_SEARCH)
            client.newCall(searchMangaByIdRequest(id))
                .asObservableSuccess()
                .map { response -> searchMangaByIdParse(response, id) }
        }

        query.toIntOrNull() != null -> {
            client.newCall(searchMangaByIdRequest(query))
                .asObservableSuccess()
                .map { response -> searchMangaByIdParse(response, query) }
        }

        else -> super.fetchSearchManga(page, query, filters)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val nhLangSearch = if (nhLang.isBlank()) "" else "language:$nhLang "
        val advQuery = combineQuery(filterList)
        val favoriteFilter = filterList.findInstance<FavoriteFilter>()
        val offsetPage =
            filterList.findInstance<OffsetPageFilter>()?.state?.toIntOrNull()?.plus(page) ?: page

        return if (favoriteFilter?.state == true) {
            val url = "$baseUrl/favorites/".toHttpUrl().newBuilder()
                .addQueryParameter("q", "$query $advQuery")
                .addQueryParameter("page", offsetPage.toString())
            GET(url.build(), headers)
        } else {
            val url = "$baseUrl/search/".toHttpUrl().newBuilder()
                .addQueryParameter("q", "$query $nhLangSearch$advQuery".ifBlank { "\"\"" })
                .addQueryParameter("page", offsetPage.toString())

            filterList.findInstance<SortFilter>()?.let { f ->
                url.addQueryParameter("sort", f.toUriPart())
            }

            GET(url.build(), headers)
        }
    }

    private fun combineQuery(filters: FilterList): String = buildString {
        filters.filterIsInstance<AdvSearchEntryFilter>().forEach { filter ->
            filter.state.split(",")
                .map(String::trim)
                .filterNot(String::isBlank)
                .forEach { tag ->
                    val quoted = !(filter.name == "Pages" || filter.name == "Uploaded")
                    if (tag.startsWith("-")) append("-")
                    append(filter.name, ':')
                    if (quoted) append('"')
                    append(tag.removePrefix("-"))
                    if (quoted) append('"')
                    append(" ")
                }
        }
    }

    private fun searchMangaByIdRequest(id: String) = GET("$baseUrl/g/$id", headers)

    private fun searchMangaByIdParse(response: Response, id: String): MangasPage {
        val details = mangaDetailsParse(response)
        details.url = "/g/$id/"
        return MangasPage(listOf(details), false)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        if (response.request.url.toString().contains("/login/")) {
            val document = response.asJsoup()
            if (document.select(".fa-sign-in").isNotEmpty()) {
                throw Exception("Log in via WebView to view favorites")
            }
        }
        return super.searchMangaParse(response)
    }

    override fun searchMangaFromElement(element: Element) = latestUpdatesFromElement(element)

    override fun searchMangaSelector() = latestUpdatesSelector()

    override fun searchMangaNextPageSelector() = latestUpdatesNextPageSelector()

    // ── Detail ───────────────────────────────────────────────────────────────

    override fun mangaDetailsParse(document: Document): SManga {
        val h1 = document.selectFirst("h1.title")
        val prettyTitle = h1?.selectFirst(".pretty")?.text()?.trim() ?: h1?.text()?.trim() ?: ""
        val fullTitle = h1?.text()?.trim() ?: prettyTitle

        // Thumbnail is the cover image; its URL carries the media_id
        val coverUrl = document.selectFirst("#cover img")?.attr("src") ?: ""

        // Each .tag-container starts with a plain-text label like "Artists: "
        val tagMap = document.select("#tags .tag-container").associate { container ->
            val label = container.ownText().substringBefore(":").trim()
            val tagNames = container.select("a.tag .name").map { it.text() }
            label to tagNames
        }

        val artists = tagMap["Artists"]?.joinToString(", ") ?: ""
        val groups = tagMap["Groups"]?.joinToString(", ")?.takeIf { it.isNotBlank() }
        val tags = tagMap["Tags"]?.joinToString(", ") ?: ""
        val categories = tagMap["Categories"]?.joinToString(", ") ?: ""
        val parodies = tagMap["Parodies"]?.joinToString(", ") ?: ""
        val characters = tagMap["Characters"]?.joinToString(", ") ?: ""
        val pages = tagMap["Pages"]?.firstOrNull() ?: ""
        val languages = tagMap["Languages"]?.joinToString(", ") ?: ""

        val japaneseTitle = document.selectFirst("h2.title")?.text()?.trim()

        return SManga.create().apply {
            title = if (displayFullTitle) fullTitle else prettyTitle.shortenTitle()
            thumbnail_url = coverUrl
            status = SManga.COMPLETED
            artist = artists
            author = groups ?: artists
            description = buildString {
                if (!pages.isNullOrBlank()) append("Pages: $pages\n")
                if (!japaneseTitle.isNullOrBlank()) append("Japanese: $japaneseTitle\n")
                if (parodies.isNotBlank()) append("Parodies: $parodies\n")
                if (characters.isNotBlank()) append("Characters: $characters\n")
                if (categories.isNotBlank()) append("Categories: $categories\n")
                if (languages.isNotBlank()) append("Languages: $languages\n")
            }.trim()
            genre = tags
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        }
    }

    // ── Chapters ─────────────────────────────────────────────────────────────

    override fun chapterListRequest(manga: SManga): Request = GET("$baseUrl${manga.url}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        // Try attribute selector first; fall back to iterating all <time> elements
        // (SvelteKit comment nodes can occasionally interfere with attribute selectors)
        val dateStr = document.selectFirst("time[datetime]")?.attr("datetime")
            ?: document.select("time").firstOrNull { it.hasAttr("datetime") }?.attr("datetime")
        val uploadDate = if (dateStr != null) parseDatetime(dateStr) else 0L

        val groups = document.select("#tags .tag-container")
            .firstOrNull { it.ownText().startsWith("Groups") }
            ?.select("a.tag .name")
            ?.map { it.text() }
            ?.joinToString(", ")
            ?.takeIf { it.isNotBlank() }

        return listOf(
            SChapter.create().apply {
                name = "Chapter"
                scanlator = groups
                date_upload = uploadDate
                setUrlWithoutDomain(response.request.url.encodedPath)
            },
        )
    }

    override fun chapterFromElement(element: Element) = throw UnsupportedOperationException()

    override fun chapterListSelector() = throw UnsupportedOperationException()

    // ── Pages ────────────────────────────────────────────────────────────────

    override fun pageListParse(document: Document): List<Page> {
        return document.select("a.gallerythumb img").mapIndexed { i, img ->
            Page(index = i, imageUrl = thumbToImageUrl(img.attr("src")))
        }
    }

    // CDN thumbnail URL patterns observed:
    //   1t.jpg.webp  →  full image: 1.jpg   (JPEG original, CDN adds .webp to thumb)
    //   2t.webp.webp →  full image: 2.webp  (WebP original, CDN adds extra .webp to thumb)
    //   3t.webp      →  full image: 3.webp  (native WebP, no extra suffix)
    // Rule: strip 't' marker, then strip CDN-appended trailing .webp if something remains.
    private fun thumbToImageUrl(src: String): String {
        if (src.isBlank()) return src

        val scheme = src.substringBefore("://") + "://"
        val afterScheme = src.substringAfter("://")
        val host = afterScheme.substringBefore("/")
        val path = afterScheme.substringAfter(host)

        // t3.nhentai.net → i3.nhentai.net
        val imageHost = if (host.length > 1 && host[0] == 't' && host[1].isDigit()) {
            "i" + host.drop(1)
        } else {
            host
        }

        val dir = path.substringBeforeLast("/")
        val file = path.substringAfterLast("/")

        // Step 1: "1t.jpg.webp" → "1.jpg.webp"  (strip page-thumbnail 't' marker)
        val withoutT = file.replace(Regex("""^(\d+)t\."""), "$1.")

        // Step 2: strip CDN-appended trailing .webp only when a real extension still remains
        //   "1.jpg.webp" → stripped="1.jpg" → has dot → use "1.jpg"
        //   "2.webp.webp" → stripped="2.webp" → has dot → use "2.webp"
        //   "3.webp"      → stripped="3"      → no dot  → keep "3.webp"
        val cleanFile = withoutT.substringBeforeLast(".webp").let { stripped ->
            if (stripped.contains('.')) stripped else withoutT
        }

        return "$scheme$imageHost$dir/$cleanFile"
    }

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException()

    // ── Filters ──────────────────────────────────────────────────────────────

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Separate tags with commas (,)"),
        Filter.Header("Prepend with dash (-) to exclude"),
        TagFilter(),
        CategoryFilter(),
        GroupFilter(),
        ArtistFilter(),
        ParodyFilter(),
        CharactersFilter(),
        Filter.Header("Uploaded valid units are h, d, w, m, y."),
        Filter.Header("example: (>20d)"),
        UploadedFilter(),
        Filter.Header("Filter by pages, for example: (>20)"),
        PagesFilter(),

        Filter.Separator(),
        SortFilter(),
        OffsetPageFilter(),
        Filter.Header("Sort is ignored if favorites only"),
        FavoriteFilter(),
    )

    class TagFilter : AdvSearchEntryFilter("Tags")
    class CategoryFilter : AdvSearchEntryFilter("Categories")
    class GroupFilter : AdvSearchEntryFilter("Groups")
    class ArtistFilter : AdvSearchEntryFilter("Artists")
    class ParodyFilter : AdvSearchEntryFilter("Parodies")
    class CharactersFilter : AdvSearchEntryFilter("Characters")
    class UploadedFilter : AdvSearchEntryFilter("Uploaded")
    class PagesFilter : AdvSearchEntryFilter("Pages")
    open class AdvSearchEntryFilter(name: String) : Filter.Text(name)

    class OffsetPageFilter : Filter.Text("Offset results by # pages")

    private class FavoriteFilter : Filter.CheckBox("Show favorites only", false)

    private class SortFilter : UriPartFilter(
        "Sort By",
        arrayOf(
            Pair("Popular: All Time", "popular"),
            Pair("Popular: Month", "popular-month"),
            Pair("Popular: Week", "popular-week"),
            Pair("Popular: Today", "popular-today"),
            Pair("Recent", "date"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private inline fun <reified T> Iterable<*>.findInstance() = find { it is T } as? T

    companion object {
        const val PREFIX_ID_SEARCH = "id:"
        private const val TITLE_PREF = "Display manga title as:"
    }
}
