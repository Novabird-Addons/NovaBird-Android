# Herkunft

Unveränderte Kopie des Prüfkerns aus dem Thunderbird-Add-on „Novabird-phishing“ 0.9.1 (MIT), Repo
`Novabird-Addons/Phishing`, Branch `main`, Commit `bacb347` (Release 0.9.1, 04.11.2026: lineare HTML-Prüfung, lange
Anzeigenamen gekürzt, Bank-Eintrag entfernt). Ausnahme: `lib/authserv.js` (Lernen der authserv-id je Konto) ist in Kotlin
nachgebaut (`ProtectionStore.countAuthserv`, gleiche Grenzen); dessen Test fehlt daher in `analyzer.test.js`.

Mitgeliefert: `source/src/lib/` ohne die Dateien, die an Thunderbird oder am Browser hängen, dazu `source/src/data/brands.js`,
`source/src/data/banks-wikidata.js` (Wikidata, CC0) und `source/src/i18n.js` (Texte der Signale, DE/EN).

Nicht mitgeliefert: `lib/anonymize.js` (Export anonymisierter Mails), `lib/modelcheck.js` (OCR-Modelle im Browser),
`data/ocr-languages.js`, alles außerhalb von `lib/` (Hintergrundskript, Banner, OCR/QR über WebAssembly). Diese Teile
liefert die App in Kotlin.

Nicht hier ändern: Änderungen gehören ins Add-on und werden dann neu kopiert (Dateien ersetzen, `manifest.txt` neu
erzeugen: `find source -name "*.js" | sort > manifest.txt`). Die Tests des Add-ons liegen unverändert unter
`src/jvmTest/resources/novabird-phishing/tests/` und laufen in `AddonTestSuiteTest` unter QuickJS.

Die Verbindung zu Kotlin liefern `novabird-protection/glue.js` und `ProtectionEngine`.
