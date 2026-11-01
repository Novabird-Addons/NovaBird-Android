// Ergänzungen für QuickJS, die der Suchkern erwartet.

// structuredClone ist kein Teil von ECMAScript und fehlt in QuickJS (genutzt in chips.js, resolvePeople.js).
if (typeof globalThis.structuredClone !== "function") {
  const clone = (v) => {
    if (v === null || typeof v !== "object") return v;
    if (v instanceof Date) return new Date(v.getTime());
    if (Array.isArray(v)) return v.map(clone);
    if (v instanceof Map) return new Map([...v].map(([k, x]) => [clone(k), clone(x)]));
    if (v instanceof Set) return new Set([...v].map(clone));
    const out = {};
    for (const k of Object.keys(v)) out[k] = clone(v[k]);
    return out;
  };
  globalThis.structuredClone = clone;
}
