# Sprachdaten für die Texterkennung (OCR)

| Datei | Größe | SHA-256 |
|---|---|---|
| `deu.traineddata` | 1.525.436 Byte | `19d219bbb6672c869d20a9636c6816a81eb9a71796cb93ebe0cb1530e2cdb22d` |
| `eng.traineddata` | 4.113.088 Byte | `7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2` |

- Quelle: https://github.com/tesseract-ocr/tessdata_fast (fast-Modelle), Lizenz Apache-2.0 (`LICENSE-Apache-2.0.txt`).
- Unverändert übernommen aus dem Phishing-Add-on (Fork `novabird/android`, `source/src/vendor/tesseract-wasm/`),
  Prüfsummen gegen dessen `SHA256SUMS` geprüft.
- Gelesen von Tesseract4Android (`AndroidMediaScanner`); die App kopiert sie beim ersten Gebrauch in ihre Dateien,
  weil Tesseract aus einer Datei liest. Größen dort in `LANGUAGE_FILES` – bei einem Austausch mit ändern.
