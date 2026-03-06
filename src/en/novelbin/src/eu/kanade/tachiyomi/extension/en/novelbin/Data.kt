package eu.kanade.tachiyomi.extension.en.novelbin

import android.os.Build
import androidx.annotation.RequiresApi
import keiyoushi.utils.toJsonString
import kotlinx.serialization.Serializable
import java.util.Base64

@Serializable
data class Html2CanvasReqStyle(
    val hide: List<String>,
    val fontSize: String,
    val addMarginTopTo: String,
    val marginTop: String,
    val marginStart: String?,
    val marginEnd: String?,
    val marginBottom: String?,
    val padding: String?,
)

@Serializable
data class Html2CanvasReq(
    val qSelector: String,
    val url: String,
    val fallbackImage: String,
    val style: Html2CanvasReqStyle?,
) {
    @RequiresApi(Build.VERSION_CODES.O)
    fun toBase64EncodeToString(): String? = Base64.getUrlEncoder().encodeToString(this.toJsonString().toByteArray())
}
