package org.novabird.mail

import com.fsck.k9.ui.settings.AboutExtraLibraries
import com.fsck.k9.ui.settings.Library
import org.koin.dsl.module

private const val APACHE_2 = "Apache License, Version 2.0"
private const val MIT = "MIT License"

/** Libraries and data NovaBird adds to Thunderbird's (About screen, "Libraries"). */
internal val NOVABIRD_LIBRARIES = listOf(
    Library("AndroidSVG", "https://github.com/BigBadaboom/androidsvg", APACHE_2),
    Library(
        "Jina Embeddings v2 Base DE (semantic search, downloaded on request)",
        "https://huggingface.co/jinaai/jina-embeddings-v2-base-de",
        APACHE_2,
    ),
    Library("Leptonica", "http://www.leptonica.org/", "Leptonica License (BSD 2-Clause)"),
    Library(
        "NovaBird phishing protection (add-on core)",
        "https://github.com/Novabird-Addons/NovaBird-Android",
        MIT,
    ),
    Library("ONNX Runtime", "https://github.com/microsoft/onnxruntime", MIT),
    Library("QuickJS", "https://bellard.org/quickjs/", MIT),
    Library("quickjs-kt", "https://github.com/dokar3/quickjs-kt", APACHE_2),
    Library("SQLite", "https://www.sqlite.org/", "Public Domain"),
    Library("Tesseract OCR", "https://github.com/tesseract-ocr/tesseract", APACHE_2),
    Library(
        "Tesseract language data (tessdata_fast deu, eng)",
        "https://github.com/tesseract-ocr/tessdata_fast",
        APACHE_2,
    ),
    Library("Tesseract4Android", "https://github.com/adaptech-cz/Tesseract4Android", APACHE_2),
    Library("Wikidata (list of banks)", "https://www.wikidata.org/", "CC0 1.0"),
)

internal val novaBirdAboutModule = module {
    single<AboutExtraLibraries> { AboutExtraLibraries { NOVABIRD_LIBRARIES } }
}
