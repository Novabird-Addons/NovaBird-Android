// Ersatz für tests/helpers/node-adapter.js (better-sqlite3): derselbe Adapter wie in der App
// (glue.js, Kotlin-SQLite mit FTS5), je Aufruf eine frische Datenbank im Speicher.
import { createAndroidAdapter } from "../novabird-android/glue.js";

export function createNodeAdapter(path = ":memory:") {
  const handle = __nbOpen(path);
  const db = createAndroidAdapter(handle);
  return {
    ...db,
    kind: "node",
    async close() {
      __nbClose(handle);
    },
  };
}
