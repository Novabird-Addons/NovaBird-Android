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

/**
 * Antwort einer Suche für Kotlin. bestIds: die ersten bestIds.length Zeilen sind die besten Treffer (sort "best");
 * relevant: die besten 100 nach Relevanz mit bm25-Wert, damit Kotlin Bedeutungstreffer nach denselben Regeln einmischt.
 */
const searchResult = (r) =>
  JSON.stringify({
    query: r.query,
    chips: r.chips,
    rows: r.rows,
    empty: r.empty,
    suggest: r.suggest,
    bestIds: r.bestIds ?? [],
    relevant: r.relevant ?? [],
  });

// textWithoutChip: längere Anfragen und längere Wortstücke werden nicht durchprobiert
const CHIP_TEXT_MAX_WORDS = 30;
const CHIP_TEXT_MAX_WINDOW = 6;

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
    return searchResult(r);
  },
  async searchWithoutChip(queryJson, chipId, optionsJson) {
    const q = core.removeChip(JSON.parse(queryJson), chipId);
    const r = await core.search(db, caps, q, options(JSON.parse(optionsJson)));
    return searchResult(r);
  },
  /**
   * „Beste zuerst“ mit Bedeutungstreffern wie semanticAugment am PC: fuseBest(relevant, sem), dann bestThenDate mit
   * allen angezeigten Treffern. relevant: aus der Suche; sem: [{ header_msg_id, score (Kosinus) }]; all: angezeigte
   * Treffer [{ header_msg_id, date, i }]. Antwort: { rows, bestIds } (rows: Zeilen aus relevant/sem/all).
   */
  async fuseBestThenDate(relevantJson, semJson, allJson, optionsJson) {
    const o = options(JSON.parse(optionsJson));
    const now = o.now ? +o.now : Date.now();
    const best = core.fuseBest(JSON.parse(relevantJson), JSON.parse(semJson), { now });
    return JSON.stringify(core.bestThenDate(best, JSON.parse(allJson)));
  },
  /**
   * Suchtext ohne die Wörter, aus denen der Chip chipId entstand (nur Android; der Kern merkt sich keine Textstellen):
   * das kürzeste zusammenhängende Wortstück, ohne das der Text genau die übrigen Chips ergibt, dann nach links um
   * Wörter erweitert, die keinen Chip tragen („von“ bei „von Jonas“; nach rechts nicht: „mit“ in „mit PDF“ ist
   * entbehrlich, gehört aber zum nächsten Chip).
   * Antwort: der neue Text oder null (kein solches Stück; der Text bleibt dann, wie er ist).
   */
  async textWithoutChip(input, queryJson, chipId, optionsJson) {
    const o = options(JSON.parse(optionsJson));
    const lang = o.langs?.[0];
    const key = (q) => JSON.stringify(core.toChips(q, lang).map((c) => [c.kind, c.label]));
    const want = key(core.removeChip(JSON.parse(queryJson), chipId));
    const words = String(input).trim().split(/\s+/).filter(Boolean);
    if (!words.length || words.length > CHIP_TEXT_MAX_WORDS) return "null";
    const without = (start, end) => [...words.slice(0, start), ...words.slice(end)].join(" ");
    const matches = async (start, end) => {
      const parsed = core.parseQuery(without(start, end), { now: o.now, langs: o.langs, dateOrder: o.dateOrder });
      return key(await core.resolvePeople(db, parsed, o)) === want;
    };
    for (let size = 1; size <= Math.min(CHIP_TEXT_MAX_WINDOW, words.length); size++) {
      for (let start = 0; start + size <= words.length; start++) {
        let end = start + size;
        if (!(await matches(start, end))) continue;
        while (start > 0 && (await matches(start - 1, end))) start--;
        return JSON.stringify(without(start, end));
      }
    }
    return "null";
  },
  /**
   * Bedeutungs-Treffer (ids: Message-IDs) nach den Filtern der Anfrage wie am PC (core.filterIds): Personen, Zeitraum,
   * Anhang, Status, Schlagwort, Ordner, ohne Suchwörter und Wort-Ausschlüsse.
   * Antwort: die erlaubten Message-IDs (alle, wenn die Anfrage keine Bedingung hat).
   */
  async filterIds(queryJson, idsJson, optionsJson) {
    const ids = JSON.parse(idsJson);
    if (!ids.length) return "[]";
    const allowed = await core.filterIds(db, caps, JSON.parse(queryJson), ids, options(JSON.parse(optionsJson)));
    return JSON.stringify(allowed ? ids.filter((id) => allowed.has(id)) : ids);
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
