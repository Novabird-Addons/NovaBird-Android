// Nur für die Tests: kleiner Ersatz für Node-Buffer (files.test.js baut damit ZIP-Dateien).
class NodeBuffer extends Uint8Array {
  writeUInt32LE(v, offset) {
    new DataView(this.buffer, this.byteOffset).setUint32(offset, v, true);
  }
  writeUInt16LE(v, offset) {
    new DataView(this.buffer, this.byteOffset).setUint16(offset, v, true);
  }
}

const utf8 = (s) => unescape(encodeURIComponent(s));

// analyzer.test.js misst Laufzeiten
globalThis.performance ??= { now: () => Date.now() };

globalThis.Buffer ??= {
  from(x, encoding) {
    if (typeof x !== "string") return NodeBuffer.from(x);
    const bytes = encoding === "latin1" ? x : utf8(x);
    return NodeBuffer.from([...bytes].map((c) => c.charCodeAt(0)));
  },
  alloc: (n) => new NodeBuffer(n),
  concat(list) {
    const out = new NodeBuffer(list.reduce((n, b) => n + b.length, 0));
    let offset = 0;
    for (const b of list) {
      out.set(b, offset);
      offset += b.length;
    }
    return out;
  },
};
