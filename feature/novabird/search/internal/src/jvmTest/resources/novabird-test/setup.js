// Laufzeit-Ergänzungen, die vitest/Node bieten und QuickJS nicht: performance, URL, Intl (nur Zeitzone), setTimeout.
import "../novabird-android/polyfills.js";

globalThis.performance ??= { now: () => __nbNanoTime() / 1e6 };

// new URL("./x.json", import.meta.url): import.meta.url ist in QuickJS der Modulname, also nur Pfade auflösen.
globalThis.URL ??= class URL {
  constructor(rel, base = "") {
    const parts = String(base).split("/").slice(0, -1);
    for (const seg of String(rel).split("/")) {
      if (seg === "..") parts.pop();
      else if (seg !== "." && seg !== "") parts.push(seg);
    }
    this.href = parts.join("/");
  }
  toString() {
    return this.href;
  }
};

// Intl fehlt in QuickJS. Die Tests prüfen damit nur, dass sie in Europe/Berlin laufen. Hier wird geprüft, was
// tatsächlich zählt: rechnet Date (Ortszeit von QuickJS) mit den Versätzen von Europe/Berlin (MEZ/MESZ)?
function looksLikeBerlin() {
  const offsets = [
    [new Date(2026, 0, 15, 12), -60],
    [new Date(2026, 6, 15, 12), -120],
    [new Date(2026, 2, 29, 1), -60], // vor der Umstellung
    [new Date(2026, 2, 29, 3), -120], // nach der Umstellung (letzter Sonntag im März)
    [new Date(2026, 9, 25, 4), -60], // nach der Rückstellung (letzter Sonntag im Oktober)
  ];
  return offsets.every(([d, o]) => d.getTimezoneOffset() === o);
}
globalThis.Intl ??= {
  DateTimeFormat: () => ({ resolvedOptions: () => ({ timeZone: looksLikeBerlin() ? "Europe/Berlin" : "nicht Europe/Berlin" }) }),
};

// Zeitgeber über Kotlin (__nbSleep wartet, __nbCancelSleep beendet das Warten sofort).
let nextTimer = 1;
const cancelled = new Set();
globalThis.setTimeout ??= (fn, ms = 0, ...args) => {
  const id = nextTimer++;
  __nbSleep(id, Number(ms) || 0).then(() => {
    if (!cancelled.delete(id)) fn(...args);
  });
  return id;
};
globalThis.clearTimeout ??= (id) => {
  if (id == null) return;
  cancelled.add(id);
  __nbCancelSleep(id);
};
const intervals = new Map();
globalThis.setInterval ??= (fn, ms = 0, ...args) => {
  const id = nextTimer++;
  const tick = () => {
    intervals.set(id, setTimeout(() => {
      if (!intervals.has(id)) return;
      fn(...args);
      if (intervals.has(id)) tick();
    }, ms));
  };
  tick();
  return id;
};
globalThis.clearInterval ??= (id) => {
  const t = intervals.get(id);
  intervals.delete(id);
  if (t != null) clearTimeout(t);
};
