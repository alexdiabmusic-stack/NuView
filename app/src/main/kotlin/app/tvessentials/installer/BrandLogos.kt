package app.tvessentials.installer

import android.content.Context
import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Logos of the apps this installer offers, supplied by the project owner and bundled as 512 px artwork so the home screen
 * looks complete before anything is installed. An app without an entry falls back to its own launcher icon (once it is
 * installed) and then to a neutral glyph.
 */
object BrandLogos {
    @DrawableRes
    fun resFor(appId: String): Int? = when (appId) {
        "nuvio-tv" -> R.drawable.logo_nuvio_tv
        "implayer" -> R.drawable.logo_implayer
        "smarttube" -> R.drawable.logo_smarttube
        else -> null
    }

    private val known = listOf("nuvio-tv", "implayer", "smarttube")

    fun loadAll(context: Context): Map<String, ImageBitmap> {
        val options = BitmapFactory.Options().apply { inScaled = false }
        return known.mapNotNull { id ->
            val res = resFor(id) ?: return@mapNotNull null
            val bitmap = runCatching { BitmapFactory.decodeResource(context.resources, res, options) }.getOrNull() ?: return@mapNotNull null
            id to bitmap.asImageBitmap()
        }.toMap()
    }
}
