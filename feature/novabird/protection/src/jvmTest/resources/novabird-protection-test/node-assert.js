// Kleiner Ersatz für node:assert/strict mit den Prüfungen, die die Tests des Add-ons benutzen.

function show(v) {
  try {
    return JSON.stringify(v, (_, x) => (x instanceof Set || x instanceof Map ? [...x] : x));
  } catch {
    return String(v);
  }
}

function deepEqual(a, b) {
  if (Object.is(a, b)) return true;
  if (typeof a !== "object" || typeof b !== "object" || a === null || b === null) return false;
  if (Object.getPrototypeOf(a) !== Object.getPrototypeOf(b)) return false;
  if (a instanceof Set) return a.size === b.size && [...a].every((x) => b.has(x));
  if (a instanceof Map) return a.size === b.size && [...a].every(([k, v]) => b.has(k) && deepEqual(v, b.get(k)));
  if (a instanceof RegExp) return String(a) === String(b);
  const ka = Object.keys(a);
  const kb = Object.keys(b);
  return ka.length === kb.length && ka.every((k) => Object.prototype.hasOwnProperty.call(b, k) && deepEqual(a[k], b[k]));
}

function fail(detail, message) {
  throw new Error(message ? `${message} (${detail})` : detail);
}

const assert = {
  ok(v, message) {
    if (!v) fail(`ok: ${show(v)}`, message);
  },
  equal(a, b, message) {
    if (!Object.is(a, b)) fail(`erwartet ${show(b)}, war ${show(a)}`, message);
  },
  notEqual(a, b, message) {
    if (Object.is(a, b)) fail(`nicht erwartet: ${show(b)}`, message);
  },
  deepEqual(a, b, message) {
    if (!deepEqual(a, b)) fail(`erwartet ${show(b)}, war ${show(a)}`, message);
  },
  match(s, re, message) {
    if (!re.test(s)) fail(`${show(s)} passt nicht zu ${re}`, message);
  },
  async rejects(p, message) {
    try {
      await (typeof p === "function" ? p() : p);
    } catch {
      return;
    }
    fail("kein Fehler geworfen", message);
  },
};

export default assert;
