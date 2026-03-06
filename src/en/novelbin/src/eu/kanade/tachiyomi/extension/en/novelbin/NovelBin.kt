package eu.kanade.tachiyomi.extension.en.novelbin

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
import keiyoushi.utils.getPreferences
import keiyoushi.utils.toJsonString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup

class NovelBin :
    HttpSource(),
    ConfigurableSource {

    override val baseUrl: String = "https://novelbin.com"
    override val lang: String = "en"
    override val supportsLatest: Boolean = true
    override val name: String = "Novel Bin"

    fun getStyle(): Html2CanvasReqStyle {
        val hide: ArrayList<String> = arrayListOf()
        return Html2CanvasReqStyle(
            hide = hide,
            fontSize = "50px",
            addMarginTopTo = "p,h1,h2,h3,h4,h5,h6",
            marginTop = "30px",
            marginStart = null,
            marginEnd = null,
            marginBottom = "10px",
            padding = "30px",
        )
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val list: ArrayList<SChapter> = arrayListOf()
        val doc = Jsoup.parse(response.body.string())
        for ((index, a) in doc.select("li a").withIndex()) {
            val c = SChapter.create()
            c.setUrlWithoutDomain(a.attr("href"))
            c.chapter_number = index.toFloat()
            c.name = a.text()
            list.add(c)
        }
        return list.reversed()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun pageListRequest(chapter: SChapter): Request {
        val r = Html2CanvasReq(
            qSelector = "#chr-content",
            url = baseUrl + chapter.url,
            fallbackImage = "https://th.bing.com/th/id/OIP.YU4UmFmovboXAc9VYet8ZwHaE4?o=7rm=3&rs=1&pid=ImgDetMain&o=7&rm=3",
            style = getStyle(),
        )
        return echo(r)
    }

    override fun chapterListRequest(manga: SManga): Request {
        val id = manga.url.substringAfterLast("/").substringBefore("?")
        return GET("$baseUrl/ajax/chapter-archive?novelId=$id")
    }

    override fun imageUrlParse(response: Response): String {
        TODO("Not yet implemented")
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/sort/latest?page=$page")

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string())
        val novel = SManga.create()
        novel.url = response.request.url.toString()
        novel.thumbnail_url = doc.selectFirst(".books .book img")!!.attr("data-src")
        val info = doc.selectFirst(".col-xs-12.col-sm-8.col-md-8.desc")!!
        novel.title = info.selectFirst(".title")!!.text()
        for (el in info.select(".info.info-meta li")) {
            val key = el.selectFirst("h3")!!.text()
            when (key) {
                "Author:" -> {
                    novel.author = el.selectFirst("a")!!.text()
                }

                "Genre:" -> {
                    novel.genre = el.select("a").joinToString(", ") { it.text() }
                }
                "Status:" -> {
                    val status = el.selectFirst("a")!!.text()
                    when (status) {
                        "Ongoing" -> novel.status = SManga.ONGOING
                        "Completed" -> novel.status = SManga.COMPLETED
                        else -> novel.status = SManga.UNKNOWN
                    }
                }
            }
        }
        novel.description = doc.selectFirst("#tab-description")!!.text()
        return novel
    }

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

    override fun popularMangaParse(response: Response): MangasPage {
        val list: ArrayList<SManga> = arrayListOf()
        val doc = Jsoup.parse(response.body.string())
        for (el in doc.select(".list.list-novel.col-xs-12:not(.list-genre) .row:not(.hot-item)")) {
            val img = el.selectFirst("img.cover")!!.attr("data-src")
            val title = el.selectFirst(".novel-title a")!!
            list.add(
                SManga.create().run {
                    this.thumbnail_url = img
                    this.title = title.attr("title")
                    this.setUrlWithoutDomain(title.attr("href"))
                    this
                },
            )
        }
        return MangasPage(
            mangas = list,
            hasNextPage = list.count() >= 20,
        )
    }

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/sort/top-view-novel?page=$page")

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request = GET("$baseUrl/search?keyword=$query&page=$page")

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
