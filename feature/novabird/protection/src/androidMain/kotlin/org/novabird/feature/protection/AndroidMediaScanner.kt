package org.novabird.feature.protection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.novabird.feature.protection.resources.Res

/**
 * QR codes with ZXing and text with Tesseract (Tesseract4Android), like `qr.js` and `ocr.js` of the add-on. Images are
 * scaled down to at most 1600 px and put on white (transparent areas are unreadable otherwise).
 *
 * OCR reads German and English in one pass. The language data (`tessdata_fast`, as in the add-on) ships with the app
 * and is copied to the app's files on first use, because Tesseract reads it from a file.
 */
class AndroidMediaScanner(private val context: Context) : MediaScanner {
    /** Tesseract is not thread-safe, and two OCR runs at once would only double the memory. */
    private val ocrLock = Mutex()

    override suspend fun qrUrls(image: ByteArray): List<String> = withContext(Dispatchers.Default) {
        val bitmap = decode(image) ?: return@withContext emptyList()
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            QrCodes.urls(bitmap.width, bitmap.height, pixels)
        } finally {
            bitmap.recycle()
        }
    }

    override suspend fun text(image: ByteArray): String? = ocrLock.withLock {
        withContext(Dispatchers.Default) {
            val bitmap = decode(image) ?: return@withContext null
            try {
                if (bitmap.width < MIN_OCR_WIDTH) return@withContext null
                val dataPath = installLanguages() ?: return@withContext null
                recognize(dataPath, bitmap)
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun recognize(dataPath: String, bitmap: Bitmap): String? {
        val api = TessBaseAPI()
        return try {
            if (!api.init(dataPath, OCR_LANGUAGES)) return null
            api.setImage(bitmap)
            coroutineScope {
                val reading = async(Dispatchers.IO) { runCatching { api.getUTF8Text() }.getOrNull() }
                val text = withTimeoutOrNull(OCR_TIMEOUT_MILLIS) { reading.await() }
                if (text == null) {
                    // too slow: stop Tesseract and wait for it to return before freeing it
                    api.stop()
                    reading.await()
                }
                text?.replace(WHITESPACE, " ")?.trim()?.takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            null
        } finally {
            api.recycle()
        }
    }

    /** Copies the language data to `files/novabird-ocr/tessdata` (once) and returns the folder above `tessdata`. */
    @OptIn(ExperimentalResourceApi::class)
    @Suppress("TooGenericExceptionCaught")
    private suspend fun installLanguages(): String? {
        val root = File(context.filesDir, OCR_DIR)
        val tessdata = File(root, "tessdata")
        return try {
            for ((language, size) in LANGUAGE_FILES) {
                val target = File(tessdata, "$language.traineddata")
                if (target.length() == size) continue
                tessdata.mkdirs()
                val bytes = Res.readBytes("files/novabird-tessdata/$language.traineddata")
                if (bytes.size.toLong() != size) return null
                val temp = File(tessdata, "$language.traineddata.tmp")
                temp.writeBytes(bytes)
                if (!temp.renameTo(target)) {
                    temp.delete()
                    return null
                }
            }
            root.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    /** Decodes [bytes] at most [MAX_SIDE] px on the long side, on a white background; `null` if it is no image. */
    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val out = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(decoded, null, Rect(0, 0, out.width, out.height), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        decoded.recycle()
        return out
    }

    private companion object {
        const val MAX_SIDE = 1600
        const val MIN_OCR_WIDTH = 200
        const val OCR_TIMEOUT_MILLIS = 8_000L
        const val OCR_DIR = "novabird-ocr"
        const val OCR_LANGUAGES = "deu+eng"

        /** Language files and their sizes (see `novabird-tessdata/SOURCE.md`). */
        val LANGUAGE_FILES = mapOf("deu" to 1_525_436L, "eng" to 4_113_088L)
        val WHITESPACE = Regex("\\s+")
    }
}

/** QR codes in an image (ZXing, QR only, also inverted like `tryInvert` in the add-on). */
internal object QrCodes {
    private const val MAX_PER_IMAGE = 3
    private val HTTP = Regex("^https?://", RegexOption.IGNORE_CASE)
    private val HINTS = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    )

    fun urls(width: Int, height: Int, pixels: IntArray): List<String> {
        val source = RGBLuminanceSource(width, height, pixels)
        val texts = decode(source).ifEmpty { decode(source.invert()) }
        return texts.map { it.trim() }.filter { HTTP.containsMatchIn(it) }.distinct().take(MAX_PER_IMAGE)
    }

    private fun decode(source: LuminanceSource): List<String> = try {
        QRCodeMultiReader().decodeMultiple(BinaryBitmap(HybridBinarizer(source)), HINTS).mapNotNull { it.text }
    } catch (e: ReaderException) {
        emptyList()
    }
}
