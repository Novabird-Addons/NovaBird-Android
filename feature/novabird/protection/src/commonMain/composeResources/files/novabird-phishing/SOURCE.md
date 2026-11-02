# Herkunft

Unveränderte Kopie des Prüfkerns aus dem Thunderbird-Add-on „Novabird-phishing“ 0.9.1 (MIT, Arbeitsstand), Fork unter
`C:\Claude\NovaBird-Android\phishing`, Branch `novabird/android`, Commit `9035a01adb0be44b86babfa2bffd825ebbbb030d`
(Add-on 0.9.1 vom 02.11.2026; im Fork nur persönliche Daten in einem Kommentar und einem Test durch Platzhalter ersetzt).

Mitgeliefert: `source/src/lib/` ohne die Dateien, die an Thunderbird oder am Browser hängen, dazu `source/src/data/brands.js`,
`source/src/data/banks-wikidata.js` (Wikidata, CC0) und `source/src/i18n.js` (Texte der Signale, DE/EN).

Nicht mitgeliefert: `lib/anonymize.js` (Export anonymisierter Mails), `lib/modelcheck.js` (OCR-Modelle im Browser),
`data/ocr-languages.js`, alles außerhalb von `lib/` (Hintergrundskript, Banner, OCR/QR über WebAssembly). Diese Teile
liefert die App in Kotlin.

Nicht hier ändern: Änderungen gehören ins Add-on und werden dann neu kopiert (Dateien ersetzen, `manifest.txt` neu
erzeugen: `find source -name "*.js" | sort > manifest.txt`). Die Tests des Add-ons liegen unverändert unter
`src/jvmTest/resources/novabird-phishing/tests/` und laufen in `AddonTestSuiteTest` unter QuickJS.

Die Verbindung zu Kotlin liefern `novabird-protection/glue.js` und `ProtectionEngine`.
