// NovaBird für Android: Verbindung zwischen dem Suchkern (novabird-search/src, unverändert) und Kotlin.
//
// Kotlin stellt drei synchrone Funktionen bereit, alle Werte als JSON-Text:
//   __nbExecute(h, sql, paramsJson)  -> Zahl geänderter Zeilen (h: Datenbank, 0 = Index)
//   __nbSelect(h, sql, paramsJson)   -> JSON [[wert, …], …] (Zeilen als Arrays, wie better-sqlite3 raw())
//   __nbBatch(h, statementsJson)     -> eine Transaktion, statementsJson = [[sql, params], …]
// Daraus entsteht hier der Adapter mit execute/select/batch nach dem Muster von db/adapter.js.

import "./polyfills.js";
import * as core from "../novabird-search/src/search.js";
import { toRecord, textFromParts, attachmentsFromParts } from "../novabird-search/src/indexer/record.js";

export function createAndroidAdapter(handle = 0) {
  return {
    kind: "android",
    async execute(sql, params = []) {
      return __nbExecute(handle, sql, JSON.stringify(params ?? []));
    },
    async select(sql, params = [], columns) {
      if (!Array.isArray(columns) || !columns.length) {
        throw new Error("select() braucht eine Spaltenliste");
      }
      const rows = JSON.parse(__nbSelect(handle, sql, JSON.stringify(params ?? [])));
      return rows.map((r) => Object.fromEntries(columns.map((c, i) => [c, r[i]])));
    },
    async batch(statements) {
      __nbBatch(handle, JSON.stringify(statements.map(({ sql, params }) => [sql, params ?? []])));
    },
  };
}

const db = createAndroidAdapter();
let caps = null;

const options = (o) => ({ ...o, now: o.now == null ? undefined : new Date(o.now) });

/** Schnittstelle für Kotlin. Ein- und Ausgaben als JSON-Text. */
globalThis.NovaBird = {
  core,
  db,
  async migrate() {
    caps = await core.migrate(db);
    return JSON.stringify(caps);
  },
  async search(input, optionsJson) {
    const o = options(JSON.parse(optionsJson));
    const r = await core.search(db, caps, String(input), o);
    return JSON.stringify({ query: r.query, chips: r.chips, rows: r.rows, empty: r.empty, suggest: r.suggest });
  },
  async searchWithoutChip(queryJson, chipId, optionsJson) {
    const q = core.removeChip(JSON.parse(queryJson), chipId);
    const r = await core.search(db, caps, q, options(JSON.parse(optionsJson)));
    return JSON.stringify({ query: r.query, chips: r.chips, rows: r.rows, empty: r.empty, suggest: r.suggest });
  },
  /**
   * Bedeutungs-Treffer (ids: Message-IDs) nach den Filtern der Anfrage: Personen, Zeitraum, Anhang, Status, Schlagwort,
   * Ordner und „ohne:“, mit denselben SQL-Bedingungen wie die Wortsuche, nur ohne deren Suchwörter.
   * Antwort: die erlaubten Message-IDs.
   */
  async filterIds(queryJson, idsJson, optionsJson) {
    const ids = JSON.parse(idsJson);
    if (!ids.length) return "[]";
    const q = { ...JSON.parse(queryJson), terms: [], phrases: [], subjectTerms: [] };
    const o = { ...options(JSON.parse(optionsJson)), sort: "date", limit: -1, offset: 0, dedupe: false };
    const built = core.buildSearchSql(q, caps, o);
    const sql = `SELECT DISTINCT header_msg_id FROM (${built.sql}) WHERE header_msg_id IN (${ids.map(() => "?").join(",")})`;
    const rows = await db.select(sql, [...built.params, ...ids], ["header_msg_id"]);
    return JSON.stringify(rows.map((r) => r.header_msg_id));
  },
  /**
   * items: [{ header, part, refs }] (part wie messages.getFull) -> Datensätze wie indexer/indexer.js, dann in den Index.
   * Antwort: [{ id, subject, body }] je Datensatz für die Vektoren (Betreff und bereinigter Text wie textOf in
   * semantic/main.js; body gekürzt, Kotlin kürzt genau wie clip()).
   */
  async upsert(itemsJson, now) {
    const items = JSON.parse(itemsJson);
    const records = items.map((it) =>
      toRecord(it.header, { content: textFromParts(it.part), attachments: attachmentsFromParts(it.part), refs: it.refs }),
    );
    await core.repository.upsertMessages(db, caps, records, Number(now));
    return JSON.stringify(records.map((r) => ({ id: r.headerMessageId, subject: r.subject, body: r.body.slice(0, 1300) })));
  },
  /** keys: [{ headerMessageId, folderId }] */
  async remove(keysJson) {
    const keys = JSON.parse(keysJson);
    await core.repository.deleteMessages(db, caps, keys);
    return keys.length;
  },
  async deleteFolder(folderId) {
    await core.repository.deleteFolder(db, caps, folderId);
  },
};
