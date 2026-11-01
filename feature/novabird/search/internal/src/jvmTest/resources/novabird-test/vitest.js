// Kleiner Ersatz für vitest, damit die Tests von NovaBird-Search unverändert unter QuickJS laufen.
// Unterstützt describe/it/test (auch async), it.each und die von den Tests benutzten Matcher.

const root = { name: "", fn: null, children: [], tests: [] };
let current = root;

export function describe(name, fn) {
  current.children.push({ name, fn, children: [], tests: [] });
}

export function it(name, fn) {
  current.tests.push({ name, fn });
}
it.each = (table) => (name, fn) => {
  for (const row of table) {
    const args = Array.isArray(row) ? row : [row];
    let i = 0;
    const title = name.replace(/%[sdio]/g, () => String(args[i++]));
    it(title, () => fn(...args));
  }
};
export const test = it;

// ------------------------------------------------------------ Vergleich

function isPlainObject(v) {
  return v !== null && typeof v === "object" && !Array.isArray(v) && !(v instanceof Date) && !(v instanceof Map) && !(v instanceof Set);
}

class Asymmetric {
  constructor(test) {
    this.test = test;
  }
}

function equals(a, b) {
  if (b instanceof Asymmetric) return b.test(a);
  if (Object.is(a, b)) return true;
  if (a instanceof Date && b instanceof Date) return a.getTime() === b.getTime();
  if (Array.isArray(a) && Array.isArray(b)) return a.length === b.length && a.every((x, i) => equals(x, b[i]));
  if (a instanceof Map && b instanceof Map) return a.size === b.size && [...a].every(([k, v]) => b.has(k) && equals(v, b.get(k)));
  if (a instanceof Set && b instanceof Set) return a.size === b.size && [...a].every((x) => b.has(x));
  if (isPlainObject(a) && isPlainObject(b)) {
    // wie vitest toEqual: Schlüssel mit undefined zählen nicht
    const ka = Object.keys(a).filter((k) => a[k] !== undefined);
    const kb = Object.keys(b).filter((k) => b[k] !== undefined);
    return ka.length === kb.length && ka.every((k) => equals(a[k], b[k]));
  }
  return false;
}

function matchesObject(actual, expected) {
  if (expected instanceof Asymmetric) return expected.test(actual);
  if (Array.isArray(expected)) return Array.isArray(actual) && actual.length === expected.length && expected.every((e, i) => matchesObject(actual[i], e));
  if (isPlainObject(expected)) return isPlainObject(actual) && Object.keys(expected).every((k) => matchesObject(actual[k], expected[k]));
  return equals(actual, expected);
}

function show(v) {
  try {
    return JSON.stringify(v, (k, x) => (x instanceof Map ? [...x] : x instanceof Set ? [...x] : x === undefined ? "undefined" : x));
  } catch {
    return String(v);
  }
}

// ------------------------------------------------------------ expect

function matchers(actual, negate) {
  const check = (ok, what) => {
    if (Boolean(ok) === negate) throw new Error(`erwartet${negate ? " nicht" : ""} ${what}, erhalten ${show(actual)}`);
  };
  return {
    toBe: (e) => check(Object.is(actual, e), `toBe ${show(e)}`),
    toEqual: (e) => check(equals(actual, e), `toEqual ${show(e)}`),
    toStrictEqual: (e) => check(equals(actual, e), `toStrictEqual ${show(e)}`),
    toMatchObject: (e) => check(matchesObject(actual, e), `toMatchObject ${show(e)}`),
    toContain: (e) => check(typeof actual === "string" ? actual.includes(e) : [...actual].some((x) => Object.is(x, e)), `toContain ${show(e)}`),
    toContainEqual: (e) => check([...actual].some((x) => equals(x, e)), `toContainEqual ${show(e)}`),
    toHaveLength: (n) => check(actual?.length === n, `toHaveLength ${n}`),
    toHaveProperty: (k, v) => check(actual != null && k in Object(actual) && (v === undefined || equals(actual[k], v)), `toHaveProperty ${k}`),
    toBeGreaterThan: (n) => check(actual > n, `> ${n}`),
    toBeGreaterThanOrEqual: (n) => check(actual >= n, `>= ${n}`),
    toBeLessThan: (n) => check(actual < n, `< ${n}`),
    toBeLessThanOrEqual: (n) => check(actual <= n, `<= ${n}`),
    toBeCloseTo: (n, digits = 2) => check(Math.abs(actual - n) < Math.pow(10, -digits) / 2, `≈ ${n}`),
    toBeNull: () => check(actual === null, "null"),
    toBeUndefined: () => check(actual === undefined, "undefined"),
    toBeDefined: () => check(actual !== undefined, "definiert"),
    toBeTruthy: () => check(actual, "truthy"),
    toBeFalsy: () => check(!actual, "falsy"),
    toBeInstanceOf: (c) => check(actual instanceof c, `Instanz von ${c?.name}`),
    toMatch: (re) => check(typeof re === "string" ? String(actual).includes(re) : re.test(String(actual)), `toMatch ${re}`),
    toThrow: (e) => {
      let thrown = null;
      try {
        actual();
      } catch (err) {
        thrown = err;
      }
      const ok = thrown !== null && (e === undefined || (e instanceof RegExp ? e.test(thrown.message) : String(thrown.message).includes(e)));
      if (ok === negate) throw new Error(`erwartet${negate ? " keinen" : ""} Fehler ${e ?? ""}, erhalten ${thrown?.message ?? "keinen"}`);
    },
  };
}

export function expect(actual) {
  const m = matchers(actual, false);
  m.not = matchers(actual, true);
  const settle = (rejects) => {
    const wrap = (negate) =>
      new Proxy(
        {},
        {
          get: (_, name) => async (...args) => {
            let value;
            let failed = false;
            try {
              value = await actual;
            } catch (err) {
              value = err;
              failed = true;
            }
            if (failed !== rejects) throw new Error(rejects ? "erwartet: Promise wird abgelehnt" : `Promise abgelehnt: ${value?.message ?? value}`);
            if (rejects && name === "toThrow") return matchers(() => {
              throw value;
            }, negate).toThrow(...args);
            return matchers(value, negate)[name](...args);
          },
        },
      );
    const r = wrap(false);
    return Object.assign(r, { not: wrap(true) });
  };
  m.resolves = settle(false);
  m.rejects = settle(true);
  return m;
}
expect.arrayContaining = (items) => new Asymmetric((a) => Array.isArray(a) && items.every((e) => a.some((x) => equals(x, e))));
expect.objectContaining = (o) => new Asymmetric((a) => matchesObject(a, o));
expect.any = (c) => new Asymmetric((a) => (c === Number ? typeof a === "number" : c === String ? typeof a === "string" : c === Boolean ? typeof a === "boolean" : a instanceof c));
expect.anything = () => new Asymmetric((a) => a != null);

// ------------------------------------------------------------ Ausführen

async function collect(suite) {
  const prev = current;
  current = suite;
  try {
    if (suite.fn) await suite.fn();
  } finally {
    current = prev;
  }
  for (const child of suite.children) await collect(child);
}

async function run(suite, prefix, results) {
  const path = suite.name ? (prefix ? `${prefix} › ${suite.name}` : suite.name) : prefix;
  for (const t of suite.tests) {
    const t0 = Date.now();
    try {
      await t.fn();
      results.push({ name: `${path} › ${t.name}`, ok: true, ms: Date.now() - t0 });
    } catch (err) {
      results.push({ name: `${path} › ${t.name}`, ok: false, ms: Date.now() - t0, error: String(err?.message ?? err) });
    }
  }
  for (const child of suite.children) await run(child, path, results);
}

/** Führt alle registrierten Tests aus; liefert JSON [{ name, ok, ms, error }]. */
globalThis.__vitestRun = async () => {
  const results = [];
  try {
    await collect(root);
  } catch (err) {
    results.push({ name: "(Sammeln der Tests)", ok: false, ms: 0, error: String(err?.message ?? err) });
  }
  await run(root, "", results);
  return JSON.stringify(results);
};
