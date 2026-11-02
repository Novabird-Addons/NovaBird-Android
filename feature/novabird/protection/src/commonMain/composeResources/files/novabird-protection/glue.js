// NovaBird für Android: Verbindung zwischen dem Phishing-Kern (novabird-phishing/source/src, unverändert) und Kotlin.
//
// Kotlin übergibt und bekommt JSON-Text. Der Kontext (ctx von analyze) kommt als reine Daten, hier werden daraus die
// Maps, Sets und die Verlaufsfunktion, die der Kern erwartet:
//   contactsByName  { nameKey: [adresse, …] }          contactAddresses [adresse, …]   contactDomains [domain, …]
//   historyByName   { nameKey: { adresse: anzahl } }   history { adresse: { count, level, … } }
//   learnedNames    { schlüssel: [domain, …] }         threadFound, ownDomains, related, ai wie im Kern

import "./polyfills.js";
import { buildFeatures } from "../novabird-phishing/source/src/lib/features.js";
import { analyze, orgKey } from "../novabird-phishing/source/src/lib/analyzer.js";
import { nameKey } from "../novabird-phishing/source/src/lib/text.js";
import { parseAiFacts } from "../novabird-phishing/source/src/lib/ai.js";
import { t } from "../novabird-phishing/source/src/i18n.js";

const parse = (json) => (json ? JSON.parse(json) : {});
const mapOfSets = (o) => new Map(Object.entries(o ?? {}).map(([k, v]) => [k, new Set(v)]));
const mapOfMaps = (o) => new Map(Object.entries(o ?? {}).map(([k, v]) => [k, new Map(Object.entries(v))]));

export function toContext(c) {
  const ctx = {
    contactsByName: mapOfSets(c.contactsByName),
    contactAddresses: new Set(c.contactAddresses ?? []),
    contactDomains: new Set(c.contactDomains ?? []),
    historyByName: mapOfMaps(c.historyByName),
    history: (address) => c.history?.[address] ?? null,
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
