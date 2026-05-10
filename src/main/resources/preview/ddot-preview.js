// Role-aware coloring for ddot fences in the Markdown preview.
//
// The Markdown plugin's default fence renderer emits
//   <pre class="code-fence"><code class="language-ddot">…raw text…</code></pre>
// We re-walk every ddot code block on each preview reload, run the same
// per-line role assignment as DdotMarkdownPreviewRenderer.body() in Java
// (which the cross-impl golden corpus locks down byte-for-byte), and
// replace the block's inner HTML with role-classed spans.
//
// Triggered on DOMContentLoaded (the JCEF preview reloads the document on
// every Markdown change, so a single pass per load is enough) and as a
// MutationObserver fallback in case the page mutates content after load.

(function () {
  "use strict";

  const SEP_RE = /\.{4}|\.{2}/g;
  const COMMAND_TOKENS = new Set(["!!"]);
  const OFF = "ddot.it/off";
  const ON = "ddot.it/on";
  const OFF_SHORT = "!!off";
  const ON_SHORT = "!!on";
  const PROCESSED = "ddotProcessed";

  function isOff(s) { return s === OFF || s === OFF_SHORT; }
  function isOn(s)  { return s === ON  || s === ON_SHORT;  }

  // Match `<code class="language-ddot…">` covering the three accepted info
  // strings (ddot, ddot.it, ddotit). Use [class~="language-ddot.it"] would
  // miss attribute-encoded dots in some renderers, so do a startsWith check
  // against the full class list instead.
  function isDdotCodeBlock(el) {
    if (el.tagName !== "CODE") return false;
    for (const c of el.classList) {
      const lower = c.toLowerCase();
      if (lower === "language-ddot" || lower === "language-ddot.it" || lower === "language-ddotit") return true;
    }
    return false;
  }

  function escapeHtml(s) {
    let out = "";
    for (let i = 0; i < s.length; i++) {
      const c = s.charAt(i);
      if (c === "<") out += "&lt;";
      else if (c === ">") out += "&gt;";
      else if (c === "&") out += "&amp;";
      else if (c === '"') out += "&quot;";
      else if (c === "'") out += "&#39;";
      else out += c;
    }
    return out;
  }

  function isWhitespace(c) { return c === " " || c === "\t" || c === "\n" || c === "\r" || c === "\f" || c === "\v"; }

  function roleClass(slot, text) {
    if (text.startsWith("ddot.it") || COMMAND_TOKENS.has(text)) return "ddot-command";
    if (slot <= 0) return "ddot-subject";
    if (slot === 1) return "ddot-predicate";
    return "ddot-object";
  }

  function renderSegment(segment, slot, out) {
    let s = 0, e = segment.length;
    while (s < e && isWhitespace(segment.charAt(s))) s++;
    while (e > s && isWhitespace(segment.charAt(e - 1))) e--;
    if (s > 0) out.push(escapeHtml(segment.substring(0, s)));
    const text = segment.substring(s, e);
    if (text.length > 0) {
      out.push('<span class="' + roleClass(slot, text) + '">' + escapeHtml(text) + "</span>");
    }
    if (e < segment.length) out.push(escapeHtml(segment.substring(e)));
  }

  function renderFreeForm(text, out) {
    const trimmed = text.trim();
    if (trimmed.startsWith("ddot.it") || COMMAND_TOKENS.has(trimmed)) {
      let s = 0;
      while (s < text.length && isWhitespace(text.charAt(s))) s++;
      let e = text.length;
      while (e > s && isWhitespace(text.charAt(e - 1))) e--;
      if (s > 0) out.push(escapeHtml(text.substring(0, s)));
      out.push('<span class="ddot-command">' + escapeHtml(text.substring(s, e)) + "</span>");
      if (e < text.length) out.push(escapeHtml(text.substring(e)));
      return;
    }
    out.push(escapeHtml(text));
  }

  function renderInline(line, out, cls) {
    out.push('<span class="' + cls + '">' + escapeHtml(line) + "</span>");
  }

  function renderInactiveLine(line, out, directive) {
    const cls = directive ? "ddot-directive" : "ddot-inactive";
    out.push('<span class="' + cls + '">' + escapeHtml(line) + "</span>");
  }

  function renderTripleLine(line, out) {
    const metaStart = line.indexOf(",,");
    const body = metaStart >= 0 ? line.substring(0, metaStart) : line;
    const tail = metaStart >= 0 ? line.substring(metaStart) : "";

    let leadEnd = 0;
    while (leadEnd < body.length && isWhitespace(body.charAt(leadEnd))) leadEnd++;
    out.push(escapeHtml(body.substring(0, leadEnd)));
    const content = body.substring(leadEnd);

    SEP_RE.lastIndex = 0;
    let last = 0;
    let slot = 0;
    let any = false;
    let m;
    while ((m = SEP_RE.exec(content)) !== null) {
      any = true;
      const segment = content.substring(last, m.index);
      renderSegment(segment, slot, out);
      const sep = m[0];
      const advance = sep.length === 4 ? 2 : 1;
      out.push('<span class="ddot-sep">' + escapeHtml(sep) + "</span>");
      slot = Math.min(slot + advance, 2);
      last = SEP_RE.lastIndex;
    }
    const trailing = content.substring(last);
    if (any) renderSegment(trailing, slot, out);
    else renderFreeForm(trailing, out);

    if (tail.length > 0) {
      out.push('<span class="ddot-meta">' + escapeHtml(tail) + "</span>");
    }
  }

  function renderBody(raw) {
    const body = raw.endsWith("\n") ? raw.substring(0, raw.length - 1) : raw;
    const lines = body.split("\n");
    const out = [];
    let inMetaBlock = false;
    let off = false;
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      if (i > 0) out.push("\n");
      const trimmed = line.trim();
      if (isOff(trimmed)) { renderInactiveLine(line, out, true);  off = true;  continue; }
      if (isOn(trimmed))  { renderInactiveLine(line, out, true);  off = false; continue; }
      if (off)            { renderInactiveLine(line, out, false);              continue; }
      if (trimmed === ",,") {
        renderInline(line, out, "ddot-meta-sep");
        inMetaBlock = !inMetaBlock;
        continue;
      }
      if (inMetaBlock) {
        out.push('<span class="ddot-meta">' + escapeHtml(line) + "</span>");
        continue;
      }
      renderTripleLine(line, out);
    }
    return out.join("");
  }

  function processOne(codeEl) {
    if (codeEl.dataset[PROCESSED] === "1") return;
    // textContent gives us the raw fence body even if the markdown plugin
    // wrapped each line in a per-source-line span — those wrappers go away
    // when we replace innerHTML below, which is the same behaviour as the
    // previous in-Java generator.
    const raw = codeEl.textContent || "";
    codeEl.innerHTML = renderBody(raw);
    codeEl.dataset[PROCESSED] = "1";
  }

  function processAll(root) {
    const blocks = (root || document).querySelectorAll(
      'code.language-ddot, code[class~="language-ddot.it"], code.language-ddotit'
    );
    for (let i = 0; i < blocks.length; i++) {
      const el = blocks[i];
      if (isDdotCodeBlock(el)) processOne(el);
    }
  }

  function start() {
    processAll(document);
    // Some preview pipelines swap document.body content after the script
    // runs. Watch for additions and re-scan; the PROCESSED flag dedupes.
    if (typeof MutationObserver !== "undefined") {
      const observer = new MutationObserver(function (mutations) {
        for (const m of mutations) {
          for (const node of m.addedNodes) {
            if (node.nodeType === 1) processAll(node);
          }
        }
      });
      observer.observe(document.body || document.documentElement, { childList: true, subtree: true });
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start);
  } else {
    start();
  }
})();
