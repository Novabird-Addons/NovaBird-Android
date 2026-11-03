package org.novabird.mail.logo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.caverock.androidsvg.SVG
import com.fsck.k9.contacts.SenderLogoLoader
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import net.thunderbird.core.logging.Logger
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "NovaBirdLogos"

/**
 * Brand logos for senders without a contact photo, from BIMI: the TXT record `default._bimi.<domain>` (or of the
 * organizational domain) points to an SVG logo, which is rendered once and kept on disk. Domains without a logo are
 * remembered too, so each domain costs one lookup a week at most. Runs on Glide's worker threads (blocking is fine
 * there); only active if the user switched sender logos on.
 */
class BimiLogoLoader(
    context: Context,
    private val settings: SenderLogoSettings,
    private val logger: Logger,
    private val isFlaggedSender: (String) -> Boolean = { false },
) : SenderLogoLoader {
    // "2": logos cached before the DMARC check are not used any more
    private val cacheDir = File(context.cacheDir, "nb_logos2")
    private val locks = ConcurrentHashMap<String, Any>()
    private val http by lazy {
        OkHttpClient.Builder()
            .callTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followSslRedirects(false)
            .build()
    }

    override fun loadLogo(emailAddress: String, sizePx: Int): Bitmap? {
        // a brand logo must not make a sender look trustworthy that the phishing protection already flagged
        if (!settings.isEnabled || isFlaggedSender(emailAddress)) return null
        val domain = BimiRecords.domainOf(emailAddress)
        return domain?.let { synchronized(locks.getOrPut(it) { Any() }) { cachedOrFetch(it) } }
    }

    private fun cachedOrFetch(domain: String): Bitmap? {
        val png = File(cacheDir, "$domain.png")
        val none = File(cacheDir, "$domain.none")
        val now = System.currentTimeMillis()
        // marker with content: the last try failed (offline); empty: the domain has no logo
        val noneTtl = if (none.length() > 0) FAILURE_TTL_MS else NO_LOGO_TTL_MS
        val cached = png.isFresh(now, LOGO_TTL_MS) || none.isFresh(now, noneTtl)
        return if (cached) png.decodeOrNull() else fetchAndKeep(domain, png, none)
    }

    private fun fetchAndKeep(domain: String, png: File, none: File): Bitmap? = try {
        cacheDir.mkdirs()
        val bitmap = fetch(domain)
        if (bitmap == null) {
            none.writeBytes(ByteArray(0))
            png.delete()
        } else {
            png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
            none.delete()
        }
        bitmap
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        // offline, slow or a broken record: letters for now, try again in an hour, keep an older logo meanwhile
        logger.info(TAG) { "No logo for $domain yet: ${e::class.simpleName}" }
        none.writeText("x")
        png.decodeOrNull()
    }

    private fun fetch(domain: String): Bitmap? {
        val organizational = BimiRecords.organizationalDomain(domain)
        if (!enforcesDmarc(domain, organizational)) return null
        val url = logoUrl(domain) ?: organizational.takeIf { it != domain }?.let(::logoUrl)
        return url?.let(::download)?.let(::render)
    }

    /** DMARC policy of the domain, else of its organizational domain (as receivers apply it). */
    private fun enforcesDmarc(domain: String, organizational: String): Boolean {
        val own = BimiDns.txtRecords("_dmarc.$domain").filter { it.trim().startsWith("v=DMARC1", ignoreCase = true) }
        if (own.isNotEmpty()) return own.any { BimiRecords.enforcesDmarc(it) }
        if (organizational == domain) return false
        return BimiDns.txtRecords("_dmarc.$organizational").any { BimiRecords.enforcesDmarc(it, forSubdomain = true) }
    }

    private fun logoUrl(domain: String): String? =
        BimiDns.txtRecords("default._bimi.$domain").firstNotNullOfOrNull { BimiRecords.logoUrl(it) }

    private fun download(url: String): String? {
        val request = Request.Builder().url(url).header("Accept", "image/svg+xml").build()
        return http.newCall(request).execute().use { response ->
            val source = response.body.source()
            // BIMI logos are small (the profile asks for 32 KB); don't read more than the limit
            val tooLarge = response.isSuccessful && source.request(MAX_SVG_BYTES + 1)
            if (response.isSuccessful && !tooLarge) source.buffer.readUtf8() else null
        }
    }

    private fun File.decodeOrNull(): Bitmap? = if (exists()) BitmapFactory.decodeFile(path) else null

    @Suppress("TooGenericExceptionCaught") // AndroidSVG throws runtime exceptions on broken files too
    private fun render(svgText: String): Bitmap? = try {
        val svg = SVG.getFromString(svgText)
        val bitmap = Bitmap.createBitmap(RENDER_SIZE, RENDER_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // logos are drawn on a white square; the list cuts the picture round
        canvas.drawColor(Color.WHITE)
        svg.renderToCanvas(canvas, RectF(0f, 0f, RENDER_SIZE.toFloat(), RENDER_SIZE.toFloat()))
        bitmap
    } catch (e: Exception) {
        logger.info(TAG) { "Logo not readable: ${e::class.simpleName}" }
        null
    }

    private fun File.isFresh(now: Long, ttl: Long) = exists() && now - lastModified() in 0 until ttl

    private companion object {
        const val HTTP_TIMEOUT_MS = 4_000L
        const val MAX_SVG_BYTES = 64L * 1024
        const val RENDER_SIZE = 192
        const val PNG_QUALITY = 100
        const val LOGO_TTL_MS = 30L * 24 * 60 * 60 * 1000
        const val NO_LOGO_TTL_MS = 7L * 24 * 60 * 60 * 1000
        const val FAILURE_TTL_MS = 60L * 60 * 1000
    }
}
