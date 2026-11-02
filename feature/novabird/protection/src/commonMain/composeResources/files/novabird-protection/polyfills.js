// Ergänzungen für QuickJS, die der Phishing-Kern erwartet.

// URL fehlt in QuickJS. Der Kern liest daraus protocol, hostname, username, password und search (analyzer.js hostOf).
// Den Host rechnet Kotlin um wie ein Browser: Kleinbuchstaben, internationale Namen in Punycode (__nbHost).
if (typeof globalThis.URL !== "function") {
  const PATTERN = /^([a-z][a-z0-9+.-]*:)\/\/(?:([^@/?#]*)@)?(\[[^\]]*\]|[^/?#:]*)(?::(\d*))?([^?#]*)(\?[^#]*)?(#.*)?$/i;
  globalThis.URL = class URL {
    constructor(href) {
      const text = String(href).trim();
      const m = text.match(PATTERN);
      const hostname = m && m[3] ? __nbHost(m[3]) : null;
      if (hostname == null) throw new TypeError("Invalid URL: " + text);
      const userinfo = m[2] ?? "";
      const colon = userinfo.indexOf(":");
      this.protocol = m[1].toLowerCase();
      this.username = colon < 0 ? userinfo : userinfo.slice(0, colon);
      this.password = colon < 0 ? "" : userinfo.slice(colon + 1);
      this.hostname = hostname;
      this.port = m[4] ?? "";
      this.pathname = m[5] || "/";
      this.search = m[6] && m[6] !== "?" ? m[6] : "";
      this.hash = m[7] && m[7] !== "#" ? m[7] : "";
      this.href = text;
    }
    toString() {
      return this.href;
    }
  };
}

// TextDecoder (UTF-8) fehlt in QuickJS; files.js liest damit Namen in ZIP-Inhaltsverzeichnissen.
if (typeof globalThis.TextDecoder !== "function") {
  globalThis.TextDecoder = class TextDecoder {
    decode(bytes) {
      let escaped = "";
      for (const b of bytes ?? []) escaped += "%" + b.toString(16).padStart(2, "0");
      try {
        return decodeURIComponent(escaped);
      } catch {
        return String.fromCharCode(...(bytes ?? []));
      }
    }
  };
}
