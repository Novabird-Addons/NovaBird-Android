package org.novabird.feature.protection

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.novabird.feature.search.MailForCheck
import org.novabird.feature.search.MailPart

/**
 * Reads the images of a mail like the add-on (`qr.js`, `ocr.js`): QR codes and text. Everything stays on the device.
 * The Android part is `AndroidMediaScanner`; without one (tests) images add nothing.
 */
interface MediaScanner {
    /** http(s) addresses in QR codes of [image] (PNG, JPEG, GIF, WebP or BMP); empty if none or unreadable. */
    suspend fun qrUrls(image: ByteArray): List<String>

    /** Text in [image] (German and English), or `null` if it is too small (under 200 px wide) or unreadable. */
    suspend fun text(image: ByteArray): String?
}

/**
 * What the images and attachments of a mail add to the check: the add-on's `qrUrls`, `imageTexts` (OCR text by image
 * key, see `imageKey()` in `lib/features.js`) and `attachmentScan` (real file type and ZIP contents by file name).
 * Stored with the result, so a second pass (language model) does not read the images again.
 *
 * @property ocrDone the text in images was read, or there was nothing to read.
 */
data class MailExtras(
    val qrUrls: List<String> = emptyList(),
    val imageTexts: Map<String, String> = emptyMap(),
    val attachmentScan: JsonObject = JsonObject(emptyMap()),
    val ocrDone: Boolean = true,
) {
    fun toJson(): JsonObject = JsonObject(
        mapOf(
            "qrUrls" to JsonArray(qrUrls.map(::JsonPrimitive)),
            "imageTexts" to JsonObject(imageTexts.mapValues { JsonPrimitive(it.value) }),
            "attachmentScan" to attachmentScan,
            "ocrDone" to JsonPrimitive(ocrDone),
        ),
    )

    companion object {
        fun fromJson(text: String?): MailExtras? {
            if (text.isNullOrBlank()) return null
            return runCatching {
                val json = Json.parseToJsonElement(text).jsonObject
                MailExtras(
                    qrUrls = json["qrUrls"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                    imageTexts = json["imageTexts"]?.jsonObject
                        ?.mapNotNull { (key, value) -> value.jsonPrimitive.contentOrNull?.let { key to it } }
                        ?.toMap().orEmpty(),
                    attachmentScan = json["attachmentScan"]?.jsonObject ?: JsonObject(emptyMap()),
                    ocrDone = json["ocrDone"]?.jsonPrimitive?.booleanOrNull ?: true,
                )
            }.getOrNull()
        }
    }
}

/** Which images and attachments of a mail are read, with the limits of the add-on (`images.js`, `attachments.js`). */
internal object MailMedia {
    const val MAX_IMAGE_BYTES = 4_000_000
    const val MAX_OCR_IMAGES = 3

    /**
     * Attachments are read whole (K-9 stores them transfer-encoded, so there is no reading just the head or the end);
     * larger ones are not touched. The add-on reads up to 50 MB.
     */
    const val MAX_ATTACHMENT_BYTES = 10_000_000
    const val HEAD_BYTES = 0x8010
    const val ZIP_TAIL = 22 + 65_535
    const val ZIP_DIR_MAX = 2_000_000

    private const val MAX_IMAGES = 6
    private const val MIN_IMAGE_BYTES = 1_000L
    private const val MAX_ATTACHMENTS = 10
    private val IMAGE_TYPE = Regex("^image/(png|jpe?g|gif|webp|bmp)$")

    /** Images worth reading: stored locally, 1 KB to 4 MB (smaller ones are icons and tracking pixels), at most 6. */
    fun images(mail: MailForCheck): List<MailPart> = mail.parts
        .filter { IMAGE_TYPE.matches(it.contentType) && it.downloaded && it.size in MIN_IMAGE_BYTES..MAX_IMAGE_BYTES }
        .take(MAX_IMAGES)

    /** Attachments (parts with a file name) stored locally, at most 10. */
    fun attachments(mail: MailForCheck): List<MailPart> = mail.parts
        .filter { !it.name.isNullOrEmpty() && it.downloaded && it.size in 1L..MAX_ATTACHMENT_BYTES }
        .take(MAX_ATTACHMENTS)

    /** Key of an image's OCR text as `imageKey()` builds it from a `cid:` link: Content-ID before `@`, lower case. */
    fun imageKey(part: MailPart): String =
        part.contentId?.substringBefore('@')?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: part.name?.lowercase().orEmpty()

    /** May be a ZIP archive worth opening (add-on: `isZipArchive`; the real type is checked in JavaScript). */
    fun maybeZip(name: String): Boolean {
        val ext = FILE_EXT.find(name.lowercase())?.groupValues?.get(1).orEmpty()
        return ext == "zip" || ext.isEmpty()
    }

    /** `fileExt()` of `lib/files.js`. */
    private val FILE_EXT = Regex("\\.([a-z0-9]{1,8})$")
}
