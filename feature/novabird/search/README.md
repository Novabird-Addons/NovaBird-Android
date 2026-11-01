# NovaBird-Suche

Die Suche von NovaBird-Search (PC) auf Android: Alltagssprache („von Petra im März mit PDF“), Komposita
(„Speiseplan“ findet „Wochenspeisepläne“), Umlaute egal, „Meinten Sie“, Relevanz.

## Aufbau

| Modul | Inhalt |
|---|---|
| `:feature:novabird:search:api` | `NovaBirdSearch`, `NovaBirdSearchIndexer`, Ergebnis-Typen, `MessageLocation`, `K9AccountProvider`, `K9MailStore` |
| `:feature:novabird:search:internal` | alles andere (KMP: `commonMain` + `androidMain`, Tests als `jvmTest`) |

- **Index:** eigene Datei `novabird-search.db` (neben den K-9-Datenbanken), geöffnet mit `androidx.sqlite`
  `sqlite-bundled` (FTS5, `trigram`, `contentless_delete`, `fts5vocab`). Die K-9-Datenbank wird nicht verändert.
- **Suchkern:** JavaScript aus NovaBird-Search, unverändert kopiert nach
  `internal/src/commonMain/composeResources/files/novabird-search/` (Herkunft in `SOURCE.md`), ausgeführt in QuickJS
  über `io.github.dokar3:quickjs-kt`. `novabird-android/glue.js` baut daraus den Adapter (`execute`/`select`/`batch`
  wie `db/adapter.js`); Kotlin liefert die SQLite-Aufrufe (`SearchIndexDatabase`, `NovaBirdScriptEngine`).
- **Indexer:** `NovaBirdIndexer` liest die K-9-Tabellen `messages`, `message_parts`, `folders` nur lesend
  (Plattform-SQLite, siehe `K9MailDatabase`), baut den MIME-Baum wie `messages.getFull()` und ruft `toRecord()`,
  `textFromParts()` und `attachmentsFromParts()` aus `indexer/record.js` auf. Abgleich über Fingerabdrücke in der
  eigenen Tabelle `nb_android_messages`; angestoßen per WorkManager (`NovaBirdIndexScheduler`).
- **Suchmaske:** `NovaBirdSearchScreen(onOpenMessage, onBack)` (Compose, Bolt-Komponenten, MVI).

**QuickJS:** `quickjs-kt` bringt das originale QuickJS mit, nicht QuickJS-ng. Entscheidung vom 01.11.2026: dabei
bleiben, im Mai 2027 neu bewerten (gibt es dann eine ausgereifte, gepflegte JNI-Bindung für QuickJS-ng?). Ein Wechsel
betrifft nur `NovaBirdScriptEngine` und `ScriptSources`.

## Einbinden (App-Modul)

```kotlin
modules(novaBirdSearchModule)
single<K9AccountProvider> { K9AccountProvider { /* Konten: UUID + eigene Adressen */ } }
```

- Nach jeder Synchronisierung `get<NovaBirdIndexScheduler>().requestIndexing()`, beim Start `schedulePeriodic()`.
- Treffer öffnen: `MessageLocation(accountUuid, folderId, uid)` → `MessageReference` → vorhandene Nachrichtenansicht.

## Suchkern aktualisieren

`novabird-search/db/src` nach `composeResources/files/novabird-search/src` kopieren (ohne `indexer/indexer.js`, der nur
nach `internal/src/jvmTest/resources/novabird-search/src/indexer/` kommt), `manifest.txt` neu erzeugen
(`find src -name "*.js" | sort > manifest.txt`), `novabird-search/db/tests` nach
`internal/src/jvmTest/resources/novabird-search/tests` kopieren, Commit in beiden `SOURCE.md` eintragen, dann
`./gradlew :feature:novabird:search:internal:jvmTest`. Die vitest-Dateien laufen unverändert (Ersatz für `vitest`,
`node:fs` und den better-sqlite3-Adapter in `jvmTest/resources/novabird-test/`).
