// NovaBird für Android: Verbindung zwischen dem Phishing-Kern (novabird-phishing/source/src, unverändert) und Kotlin.
//
// Kotlin übergibt und bekommt JSON-Text. Kontakte (setContacts) und Absenderverlauf (setSenderIndex) bleiben hier
// gespeichert und gelten für jede Prüfung; je Mail kommt der Rest des Kontexts (ctx von analyze) als reine Daten dazu:
//   threadFound, ownDomains, related, ai wie im Kern. Für Tests kann der ganze Kontext auch direkt kommen:
//   contactsByName  { nameKey: [adresse, …] }          contactAddresses [adresse, …]   contactDomains [domain, …]
//   historyByName   { nameKey: { adresse: anzahl } }   history { adresse: { count, level, … } }
//   learnedNames    { schlüssel: [domain, …] }

import "./polyfills.js";
import { buildFeatures } from "../novabird-phishing/source/src/lib/features.js";
import {
  analyze, headerSuspicion, nameMatchesDomain, orgKey, relatedCouldHelp,
} from "../novabird-phishing/source/src/lib/analyzer.js";
import { nameKey } from "../novabird-phishing/source/src/lib/text.js";
import { FREEMAIL, registrableDomain } from "../novabird-phishing/source/src/lib/domains.js";
import { isEstablished, trustLevel } from "../novabird-phishing/source/src/lib/history.js";
import { extractAnchors } from "../novabird-phishing/source/src/lib/anchors.js";
import { parseAiFacts } from "../novabird-phishing/source/src/lib/ai.js";
import { t } from "../novabird-phishing/source/src/i18n.js";

const parse = (json) => (json ? JSON.parse(json) : {});
const mapOfSets = (o) => new Map(Object.entries(o ?? {}).map(([k, v]) => [k, new Set(v)]));
const mapOfMaps = (o) => new Map(Object.entries(o ?? {}).map(([k, v]) => [k, new Map(Object.entries(v))]));

const domainOf = (address) => registrableDomain(address.slice(address.lastIndexOf("@") + 1));

/** Kontakte wie getContacts() im Hintergrundskript des Add-ons: [{ names, emails }] → Namen, Adressen, Domains. */
export function contactIndex(list) {
  const idx = { byName: new Map(), addresses: new Set(), domains: new Set() };
  for (const { names = [], emails = [] } of list ?? []) {
    const lower = emails.map((e) => String(e).toLowerCase());
    for (const e of lower) {
      idx.addresses.add(e);
      idx.domains.add(domainOf(e));
    }
    for (const n of names) {
      const k = nameKey(n);
      if (!k) continue;
      if (!idx.byName.has(k)) idx.byName.set(k, new Set());
      lower.forEach((e) => idx.byName.get(k).add(e));
    }
  }
  return idx;
}

/**
 * Etablierte Absender wie buildHistoryView() im Hintergrundskript des Add-ons. senderIndex: { adresse: { n, sent,
 * names, first, last, flagged? } } (lib/history.js).
 */
export function historyView(idx) {
  const byName = new Map();
  for (const [addr, rec] of Object.entries(idx)) {
    if (!isEstablished(rec)) continue;
    for (const name of rec.names) {
      const k = nameKey(name);
      if (!k) continue;
      if (!byName.has(k)) byName.set(k, new Map());
      byName.get(k).set(addr, rec.n + rec.sent);
    }
  }
  // Gelernte Absendernamen: etabliert, nie aufgefallen, Name passt zur Domain, keine Freemail-Domain
  const learnedNames = new Map();
  for (const [addr, rec] of Object.entries(idx)) {
    if (!isEstablished(rec)) continue;
    const rd = domainOf(addr);
    if (FREEMAIL.has(rd)) continue;
    for (const name of rec.names) {
      const k = orgKey(name);
      if (!k || !nameMatchesDomain(name, rd)) continue;
      if (!learnedNames.has(k)) learnedNames.set(k, new Set());
      learnedNames.get(k).add(rd);
    }
  }
  return {
    byName,
    learnedNames,
    lookup: (addr) => {
      const level = trustLevel(idx[addr]);
      return level ? { count: idx[addr].n + idx[addr].sent * 3, level } : null;
    },
  };
}

let contacts = contactIndex([]);
let senderIndex = {};
let history = historyView({});

export function toContext(c) {
  const direct = c.contactsByName || c.contactAddresses || c.historyByName || c.history;
  const ctx = direct
    ? {
        contactsByName: mapOfSets(c.contactsByName),
        contactAddresses: new Set(c.contactAddresses ?? []),
        contactDomains: new Set(c.contactDomains ?? []),
        historyByName: mapOfMaps(c.historyByName),
        history: (address) => c.history?.[address] ?? null,
      }
    : {
        contactsByName: contacts.byName,
        contactAddresses: contacts.addresses,
        contactDomains: contacts.domains,
        historyByName: history.byName,
        history: history.lookup,
        learnedNames: history.learnedNames,
      };
  if (c.learnedNames) ctx.learnedNames = mapOfSets(c.learnedNames);
  if (c.threadFound !== undefined && c.threadFound !== null) ctx.threadFound = c.threadFound;
  if (c.ownDomains) ctx.ownDomains = c.ownDomains;
  if (c.related) ctx.related = c.related;
  if (c.ai) ctx.ai = c.ai;
  return ctx;
}

/** Texte zu den Signalen wie reasons() im Hintergrundskript des Add-ons. relatedDay: Datum des Bezugs, schon formatiert. */
export function reasons(verdict, lang, relatedDay = "") {
  const out = verdict.signals.map((s) => {
    const key = "sig_" + s.id + (s.id === "lookalikeDomain" ? "_" + s.params[2] : "");
    return { id: s.id, weight: s.weight, text: t(lang, key, s.params) };
  });
  if (verdict.related) out.push({ id: "related", weight: 0, text: t(lang, "info_related", [verdict.related.anchor, relatedDay]) });
  return out;
}

/** Schnittstelle für Kotlin. Ein- und Ausgaben als JSON-Text. */
globalThis.NovaBirdProtection = {
  /** Merkmale aus { headers, parts, qrUrls, imageTexts, attachmentScan } (headers: { name: [wert, …] }). */
  features(input, config) {
    return JSON.stringify(buildFeatures(parse(input), parse(config)));
  },
  analyze(features, context, config) {
    return JSON.stringify(analyze(parse(features), toContext(parse(context)), parse(config)));
  },
  /** Merkmale und Bewertung in einem Schritt: { features, verdict }. */
  check(input, context, config) {
    const cfg = parse(config);
    const features = buildFeatures(parse(input), cfg);
    return JSON.stringify({ features, verdict: analyze(features, toContext(parse(context)), cfg) });
  },
  /** Kontakte des Geräts: [{ names: [..], emails: [..] }]. */
  setContacts(list) {
    contacts = contactIndex(parse(list));
    return String(contacts.addresses.size);
  },
  /** Absenderverlauf { adresse: { n, sent, names, first, last, flagged? } } aus den Postfächern. */
  setSenderIndex(idx) {
    senderIndex = parse(idx);
    history = historyView(senderIndex);
    return String(Object.keys(senderIndex).length);
  },
  /** Bestell-, Sendungs- oder Rechnungsnummern aus Betreff und Text (lib/anchors.js), höchstens drei. */
  anchors(subject, text) {
    return JSON.stringify(extractAnchors(subject ?? "", text ?? "", 3));
  },
  /** Könnte ein Bezug zu einer früheren Mail die Bewertung entschärfen? (lib/analyzer.js) */
  relatedCouldHelp(verdict) {
    return String(relatedCouldHelp(parse(verdict)));
  },
  /**
   * Zählt eine frühere Mail als Bezug? Wie trustedEarlier() im Hintergrundskript, ohne den DKIM-Zweig: aus einem
   * Gesendet-Ordner, von einem Kontakt oder von einem etablierten Absender.
   */
  trustedEarlier(author, fromSentFolder) {
    if (fromSentFolder === "true") return "true";
    const address = String(author ?? "").toLowerCase();
    if (!address) return "false";
    return String(contacts.addresses.has(address) || !!trustLevel(senderIndex[address]));
  },
  /** Ist der Absendername schon allein auffällig (Marke, Bank, Adresse im Namen …)? */
  headerSuspicion(author) {
    return JSON.stringify(headerSuspicion(author ?? ""));
  },
  reasons(verdict, lang, relatedDay) {
    return JSON.stringify(reasons(parse(verdict), lang, relatedDay));
  },
  nameKey(name) {
    return nameKey(name) ?? "";
  },
  orgKey(name) {
    return orgKey(name) ?? "";
  },
  /** Antwort der KI (JSON nach docs/ki-schnittstelle.md) prüfen und auf die erlaubten Fakten kürzen; null, wenn unbrauchbar. */
  aiFacts(answer) {
    return JSON.stringify(parseAiFacts(answer ? JSON.parse(answer) : null) ?? null);
  },
  text(lang, key, params) {
    return t(lang, key, params ? JSON.parse(params) : []);
  },
};
