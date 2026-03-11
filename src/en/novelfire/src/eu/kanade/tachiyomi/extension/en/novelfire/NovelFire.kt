package eu.kanade.tachiyomi.extension.en.novelfire

import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferences
import keiyoushi.utils.toJsonString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale

class NovelFire :
    HttpSource(),
    ConfigurableSource {
    override val baseUrl: String = "https://novelfire.net"
    override val lang: String = "en"
    override val supportsLatest: Boolean = true
    override val name: String = "Novel Fire"

    @RequiresApi(Build.VERSION_CODES.N)
    override fun chapterListParse(response: Response): List<SChapter> {
        val chapters: ArrayList<SChapter> = arrayListOf()
        val doc = response.asJsoup()
        chapters += parseChapterListPage(doc)
        val lastPage = doc.select("ul.pagination li")
            .mapNotNull { it.text().trim().toIntOrNull() }
            .maxOrNull() ?: 1
        if (lastPage > 1) {
            for (page in 2..lastPage) {
                val url = response.request.url.newBuilder()
                    .setQueryParameter("page", page.toString())
                    .build()
                val request = GET(url.toString())
                val pageResponse = client.newCall(request).execute()
                chapters += parseChapterListPage(pageResponse.asJsoup())
                pageResponse.close()
            }
        }
        return chapters
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun parseChapterListPage(document: Document): ArrayList<SChapter> {
        val chapters: ArrayList<SChapter> = arrayListOf()
        val li = document.select("ul.chapter-list li")
        chapters += li.map {
            val a = it.selectFirst("a")!!
            SChapter.create().apply {
                name = a.selectFirst(".chapter-title")!!.text().trim()
                setUrlWithoutDomain(a.attr("href"))
                chapter_number = a.selectFirst(".chapter-no")!!.text().trim().toFloat()
                date_upload = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).parse(a.selectFirst(".chapter-update")!!.attr("datetime"))!!.time
            }
        }
        return chapters
    }

    override fun chapterListRequest(manga: SManga): Request = GET("$baseUrl${manga.url}/chapters?page1=&sort_by=desc")

    override fun imageUrlParse(response: Response): String {
        TODO("Not yet implemented")
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/genre-all/sort-latest-release/status-all/all-novel?page=$page")

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val info = document.selectFirst("div.novel-info")!!

        return SManga.create().apply {
            title = info.selectFirst(".novel-title")!!.text()
            author = info.selectFirst("div.author a span")!!.text()
            genre = info.select("div.categories ul li").joinToString(", ") { it.text() }
            description = document.selectFirst("#info div.summary div.content")!!.text()
            thumbnail_url = document.selectFirst("div.fixed-img .cover img")!!.attr("src")
            status = when (
                info.select(".header-stats span").filter { e -> e.selectFirst("small")!!.text().trim() == "Status" }
                    .map { it.selectFirst("strong")!!.text().trim() }.firstOrNull()
            ) {
                "Ongoing" -> SManga.ONGOING
                "Completed" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            setUrlWithoutDomain(response.request.url.toString())
        }
    }

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl${manga.url}")

    override fun pageListParse(response: Response): List<Page> {
        val list: ArrayList<Page> = arrayListOf()
        Json.decodeFromString<List<String>>(response.body.string()).forEachIndexed { i, v ->
            list.add(
                Page(
                    index = i,
                    url = v,
                    imageUrl = v,
                    uri = Uri.parse(v),
                ),
            )
        }
        return list
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun pageListRequest(chapter: SChapter): Request {
        val r = Html2CanvasReq(
            qSelector = "#content",
            url = baseUrl + chapter.url,
            fallbackImage = "https://th.bing.com/th/id/OIP.YU4UmFmovboXAc9VYet8ZwHaE4?o=7rm=3&rs=1&pid=ImgDetMain&o=7&rm=3",
            style = getStyle(),
        )
        return echo(r)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val li = document.select("ul.novel-list li")
        val mangaList = li.map {
            val a = it.selectFirst("a")!!
            SManga.create().apply {
                title = a.attr("title")
                setUrlWithoutDomain(a.attr("href"))
                thumbnail_url = "$baseUrl${it.selectFirst("img")?.attr("data-src")}"
            }
        }
        return MangasPage(mangaList, li.size >= 24)
    }

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/genre-all/sort-popular/status-all/all-novel?page=$page")

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request = GET("$baseUrl/search?keyword=$query&type=title&page=$page")

    fun getStyle(): Html2CanvasReqStyle {
        val hide: ArrayList<String> = arrayListOf()
        hide.add("#content div")
        return Html2CanvasReqStyle(
            hide = hide,
            fontSize = "50px",
            addMarginTopTo = "p,h1,h2,h3,h4,h5,h6",
            marginTop = "50px",
            marginStart = null,
            marginEnd = null,
            marginBottom = "10px",
            padding = "30px",
        )
    }

    private val defaultBackendUrl = "https://test-api.kayt.cloud"
    private val backendUrlKey = "backend_api_url"
    private val prefs: SharedPreferences = getPreferences()
    private val backendUrlPref: String
        get() = prefs.getString(backendUrlKey, defaultBackendUrl)!!.trim().removeSuffix("/")
    private val backendUrl: String
        get() = backendUrlPref

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = backendUrlKey
            title = "Base URL For The API"
            summary = "Current: ${backendUrlPref()}"
            dialogTitle = "Set API URL"
            setDefaultValue(defaultBackendUrl)
            setOnPreferenceChangeListener { pref, newValue ->
                val raw = (newValue as? String).orEmpty().trim()
                val normalized = raw.removeSuffix("/")
                val ok = normalized.startsWith("http://") || normalized.startsWith("https://")

                if (ok) {
                    pref.summary = "Current: $normalized"
                }
                ok
            }
        }
        screen.addPreference(baseUrlPref)
    }

    private fun backendUrlPref(): String = backendUrlPref

    @RequiresApi(Build.VERSION_CODES.O)
    fun echo(req: Html2CanvasReq): Request {
        val base64 = req.toBase64EncodeToString()!!
        val url = "$backendUrl/html2canvas/$base64.png"
        return POST(url = "$backendUrl/echoBody", body = listOf(url).toJsonString().toRequestBody("application/json".toMediaType()))
    }
}
