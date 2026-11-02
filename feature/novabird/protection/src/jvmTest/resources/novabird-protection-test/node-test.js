// Kleiner Ersatz für node:test, damit die Tests des Add-ons unverändert unter QuickJS laufen.
const tests = [];

export function test(name, fn) {
  tests.push({ name, fn });
}
export default test;

globalThis.__nodeTestRun = async () => {
  const results = [];
  for (const t of tests) {
    const start = Date.now();
    try {
      await t.fn();
      results.push({ name: t.name, ok: true, ms: Date.now() - start });
    } catch (e) {
      results.push({ name: t.name, ok: false, ms: Date.now() - start, error: String(e?.message ?? e) });
    }
  }
  return JSON.stringify(results);
};
