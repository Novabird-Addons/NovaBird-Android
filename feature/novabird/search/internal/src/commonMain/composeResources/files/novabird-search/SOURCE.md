# Herkunft

Unveränderte Kopie (ohne `indexer/indexer.js`, siehe unten) von `novabird-search/db/src` aus dem NovaBird-Repository
(Branch `novabird-2.0`, Commit `16b7058` (Abwertung älterer Mails aus 9a51390), Stand 07.11.2026).

Nicht hier ändern: Änderungen gehören in NovaBird-Search und werden dann neu kopiert
(Ordner `src` ersetzen, `src/indexer/indexer.js` stattdessen nach
`internal/src/jvmTest/resources/novabird-search/src/indexer/` legen, `manifest.txt` neu erzeugen:
`find src -name "*.js" | sort > manifest.txt`).

Nicht mitgeliefert: `indexer/indexer.js` (Indexer der Thunderbird-Variante, hängt an der Thunderbird-API). Er liegt
nur in den Testressourcen, weil `tests/indexer.test.js` ihn braucht. Den Indexer für Android liefert `NovaBirdIndexer`.

Mitgeliefert, aber ungenutzt: `db/adapter.js` (Experiment-API; `search.js` exportiert daraus). Den Adapter für Android
liefern `SearchIndexDatabase`/`NovaBirdScriptEngine` in Kotlin plus `novabird-android/glue.js`.
