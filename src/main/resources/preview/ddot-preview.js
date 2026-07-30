// Role-aware coloring for ddot fences in the Markdown preview.
//
// The Markdown plugin's default fence renderer emits
//   <pre class="code-fence"><code class="language-ddot">…raw text…</code></pre>
// We re-walk every ddot code block on each preview reload, run the same
// per-line role assignment as DdotMarkdownPreviewRenderer.body() in Java
// (which the cross-impl golden corpus locks down byte-for-byte), and
// replace the block's inner HTML with role-classed spans.
//
// The line/slot model is a faithful port of the corpus-pinned positional
// tokenizer com.calpano.ddot.it.token.DdotTokenizer (which mirrors the
// TextMate grammar): tokenize each line into {start, end, token} spans, then
// wrap each span in a role-classed <span>. Normative rules:
// site/spec/ddot-it-parse.adoc (token counting, the gate, off/on, !!block)
// and site/spec/ddot-it-highlight.adoc (roles D1-D9).
//
// Canonical HTML shape: ddot-render.rb (regenerates the golden corpus at
// ../ddot.it/test-data/cases/*/expected.body.html). This file must produce
// byte-identical output — `node scripts/test-preview-js.mjs` asserts it.
//
// Triggered on DOMContentLoaded (the JCEF preview reloads the document on
// every Markdown change, so a single pass per load is enough) and as a
// MutationObserver fallback in case the page mutates content after load.

(function () {
  "use strict";

  // --- counted tokens (Parse Spec, "Token Sequences") ---------------------
  // Runs of EXACTLY n, neither preceded nor followed by the same character.
  // WS per the Parse Spec: TAB plus any Unicode space separator (Zs), so a
  // NBSP between slots separates exactly like a space. Spelled out rather than
  // `\p{Zs}` because these sources are compiled without the `u` flag — and it
  // cannot simply be added, since the `\/` escapes below are invalid in
  // unicode mode. This class is the full Zs category plus TAB.
  const WS_SRC =
    "[\\t\\u0020\\u00A0\\u1680\\u2000-\\u200A\\u202F\\u205F\\u3000]";
  const DT2_SRC = "(?<!\\.)\\.{2}(?!\\.)";
  const QUAD_SRC = "(?<!\\.)(?:\\.{4}|\\.{2}" + WS_SRC + "+\\.{2})(?!\\.)";
  const CM2_SRC = "(?<!,),,(?!,)";
  const SC2_SRC = "(?<!;);;(?!;)";

  // A command is recognised by SHAPE (Highlight Spec D6): `!!name`,
  // `ddot.it/name`, `http(s)://ddot.it/name` with a non-empty name. `!!` is
  // an EM2 (exactly two `!`), so `!!!off` is not a command prefix. `?` and
  // `#` do not terminate a command; they introduce its query/fragment.
  const CMD_PREFIX_SRC = "(?:(?:https?:\\/\\/)?ddot\\.it\\/|(?<!!)!!(?!!))";
  const CMD_SRC = CMD_PREFIX_SRC + "[^\\s?#]+(?:\\?[^\\s#]*)?(?:#\\S*)?";

  const CMD_ONLY = new RegExp("^" + CMD_SRC + "$");
  // A command nested in field text: preceded by start-of-slot or whitespace.
  const NESTED_CMD_SRC = "(?<!\\S)" + CMD_SRC;

  // `!!off` / `!!on` are recognised ANYWHERE on a line (Parse Spec
  // "pre-parse-recognition") — commonly inside a host comment
  // (`<!-- ddot.it/off -->`, `# !!on`). `(?![^\s?#])` pins the exact NAME,
  // so `!!office` is not `!!off`.
  const OFF_LINE = new RegExp("(" + CMD_PREFIX_SRC + "off)(?![^\\s?#])");
  const ON_LINE = new RegExp("(" + CMD_PREFIX_SRC + "on)(?![^\\s?#])");

  // A `!!block` opener must END its physical line (block-as-field).
  const BLOCK_OPENER_SRC = "(" + CMD_PREFIX_SRC + "block)(?:(\\?end=)(\\S+))?" + WS_SRC + "*$";

  // A line-initial `,,` or `;;` followed by a meta pair continues the
  // previous logical line as metadata (Parse Spec "block-continuation").
  const META_CONTINUATION = new RegExp(
    "^" + WS_SRC + "*(?:(" + CM2_SRC + ")|(" + SC2_SRC + "))(?=" + WS_SRC + "+" + DT2_SRC + ")"
  );

  // QUAD before DT2 so `.. ..` is ONE operator, not two.
  const SIGNIFICANT_SRC = QUAD_SRC + "|" + DT2_SRC + "|" + CM2_SRC + "|" + SC2_SRC;
  const SIGNIFICANT = new RegExp(SIGNIFICANT_SRC);

  // THE GATE (How-To-Highlight D5): a line is ddot.it only when it carries a
  // complete operator skeleton — two DT2, or one DT4 / `.. ..`. A lone `..`
  // or an ellipsis in prose stays entirely plain (23-not-a-triple).
  const GATE = new RegExp(
    "(?:(?<!\\.)\\.{4}(?!\\.)|(?<!\\.)\\.{2}(?!\\.).*(?<!\\.)\\.{2}(?!\\.))"
  );

  // --- token type → CSS class ----------------------------------------------
  const TOKEN_CLASS = {
    "subject": "ddot-subject",
    "relation": "ddot-predicate",
    "object": "ddot-object",
    "doubledot": "ddot-sep",
    "meta-doubledot": "ddot-sep",
    "meta-delim": "ddot-meta-sep",
    "meta-separator": "ddot-meta-sep",
    "meta-relation": "ddot-meta-relation",
    "meta-object": "ddot-meta-object",
    "meta-text": "ddot-meta-text",
    "command": "ddot-command",
    "command-param": "ddot-command-param",
    "block-end": "ddot-block-end",
    "excluded": "ddot-inactive",
    "verbatim": "ddot-verbatim"
  };

  // Slots that may carry a nested in-text command span (Highlight Spec D6).
  const SLOT_TOKENS = new Set([
    "subject", "relation", "object", "meta-relation", "meta-object", "meta-text"
  ]);

  const PROCESSED = "ddotProcessed";

  // WS per the Parse Spec: TAB plus any Unicode space separator (Zs). NBSP is
  // Zs and is stripped between slots exactly like a space; U+200B and U+FEFF
  // are Cf, not Zs, so they stay inside the name that contains them.
  const ZS_RE = /\p{Zs}/u;

  function isWhitespace(c) {
    return c === " " || c === "\t" || c === "\n" || c === "\r" || c === "\f" || c === "\v"
        || ZS_RE.test(c);
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

  // Match RE against line at or after `from` (Java Matcher.find(from)).
  function findFrom(src, line, from) {
    const re = new RegExp(src, "g");
    re.lastIndex = from;
    return re.exec(line);
  }

  // --- tokenizer: port of DdotTokenizer#tokenize ---------------------------

  function addTrimmed(list, line, type) {
    let s = 0, e = line.length;
    while (s < e && isWhitespace(line.charAt(s))) s++;
    while (e > s && isWhitespace(line.charAt(e - 1))) e--;
    if (s < e) list.push({ start: s, end: e, type: type });
  }

  function startsWithOperator(line) {
    const m = SIGNIFICANT.exec(line);
    return m !== null && line.substring(0, m.index).trim().length === 0;
  }

  // BLOCK_OPENER groups are contiguous: group1 starts at m.index, group2
  // (`?end=`) directly follows it, group3 (the marker) follows group2.
  function emitBlockOpener(list, m) {
    const s1 = m.index, e1 = s1 + m[1].length;
    list.push({ start: s1, end: e1, type: "command" });
    if (m[2] !== undefined) {
      const e2 = e1 + m[2].length;
      list.push({ start: e1, end: e2, type: "command-param" });
      list.push({ start: e2, end: e2 + m[3].length, type: "block-end" });
    }
  }

  function emitSlot(list, line, lo, hi, phase) {
    let s = lo, e = hi;
    while (s < e && isWhitespace(line.charAt(s))) s++;
    while (e > s && isWhitespace(line.charAt(e - 1))) e--;
    if (s >= e) return;

    const text = line.substring(s, e);
    let type;
    if (CMD_ONLY.test(text)) {
      type = "command";   // a slot whose whole text is a command IS a command
    } else {
      type = {
        subject: "subject",
        relation: "relation",
        object: "object",
        meta_rel: "meta-relation",
        meta_obj: "meta-object",
        meta_start: "meta-text"   // text after `,,` with no `..`
      }[phase];
    }
    list.push({ start: s, end: e, type: type });
  }

  // A `!!block` opener is valid ONLY in a value position. The spec names four
  // (Parse Spec "block-as-field"): Subject, Object, MetaObject, and the free
  // meta text after `,,` ("meta_start"). Relation and MetaRelation hold names,
  // so a `!!block` there is ordinary in-text command text.
  function opensBlockIn(phase) {
    return phase === "subject" || phase === "object"
      || phase === "meta_obj" || phase === "meta_start";
  }

  // Emit one line as a sequence of slots and operators, starting in `phase`.
  // Mirrors the parse automaton: the object may contain `..`, `;;` separates
  // inline meta pairs only, and a `!!block` that ends the line opens a
  // verbatim region.
  function emitLine(list, line, phase, inBlock) {
    const st = { opensBlock: false, opensMeta: false, blockEnd: null };
    const sig = new RegExp(SIGNIFICANT_SRC, "g");
    let last = 0;
    let m;

    while ((m = sig.exec(line)) !== null) {
      let sep = m[0];
      const sepStart = m.index;
      let sepEnd = sepStart + sep.length;

      let isQuad = sep.length === 4 || sep.indexOf(" ") >= 0 || sep.indexOf("\t") >= 0;
      const isComma = sep === ",,";
      const isSemi = sep === ";;";

      // Once in an object-ish phase, `..` is content, not a separator.
      if (!isComma && !isSemi && (phase === "object" || phase === "meta_obj")) continue;
      // `;;` separates inline meta pairs ONLY; inside a `,,` block it is
      // ordinary content (28-semicolon-in-meta-block).
      if (isSemi && (inBlock || phase !== "meta_obj")) continue;
      if (isComma && phase !== "object") continue;

      // The relation-closing operator is always a plain `..`, never the
      // untyped `.. ..` form — otherwise an object starting with `..` (a
      // relative path) merges with the closing marker (20-object-dotdot).
      if (isQuad && sep.length !== 4 && (phase === "relation" || phase === "meta_rel")) {
        isQuad = false;
        sep = line.substring(sepStart, sepStart + 2);
        sepEnd = sepStart + 2;
      }

      // A `!!block` ending the line makes the pending slot a block opener —
      // but only when that slot is a value position (block-as-field).
      if (opensBlockIn(phase)) {
        const bo = findFrom(BLOCK_OPENER_SRC, line, last);
        if (bo !== null && line.substring(last, bo.index).trim().length === 0
            && bo.index < m.index) {
          emitBlockOpener(list, bo);
          st.opensBlock = true;
          st.blockEnd = bo[3] !== undefined ? bo[3] : null;
          return st;
        }
      }

      emitSlot(list, line, last, sepStart, phase);
      const sepType = isComma ? "meta-delim" : isSemi ? "meta-separator"
        : (phase === "meta_start" || phase === "meta_rel" || phase === "meta_obj" || inBlock)
          ? "meta-doubledot" : "doubledot";
      list.push({ start: sepStart, end: sepEnd, type: sepType });
      last = sepEnd;

      switch (phase) {
        case "subject": phase = isQuad ? "object" : "relation"; break;
        case "relation": phase = "object"; break;
        case "object": phase = isComma ? "meta_start" : "object"; break;
        // The untyped meta form `,, .... obj` skips the meta relation.
        case "meta_start": phase = isQuad ? "meta_obj" : "meta_rel"; break;
        case "meta_rel": phase = "meta_obj"; break;
        case "meta_obj": phase = isSemi ? "meta_start" : "meta_obj"; break;
      }
    }

    // Trailing slot, or a block opener with no separator after it. The
    // meta-text position lands here: `… ,, !!block` leaves phase "meta_start"
    // with nothing but the opener left on the line.
    if (opensBlockIn(phase)) {
      const bo = findFrom(BLOCK_OPENER_SRC, line, last);
      if (bo !== null && line.substring(last, bo.index).trim().length === 0) {
        emitBlockOpener(list, bo);
        st.opensBlock = true;
        st.blockEnd = bo[3] !== undefined ? bo[3] : null;
        return st;
      }
    }
    if (line.substring(last).trim().length === 0 && phase === "meta_start") {
      st.opensMeta = true;   // trailing `,,` opened a block
    } else {
      emitSlot(list, line, last, line.length, phase);
    }
    return st;
  }

  function tokenize(lines) {
    const toks = [];
    for (let i = 0; i < lines.length; i++) toks.push([]);

    let off = false;         // inside !!off … !!on
    let verbatim = false;    // inside a !!block body
    let blockEnd = null;     // custom ?end= marker, null = blank-line form
    let inMetaBlock = false; // inside a ,, … ,, block

    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      const trimmed = line.trim();
      const list = toks[i];

      // --- excluded region: inactive, NO role spans ----------------------
      if (off) {
        const on = ON_LINE.exec(line);
        if (on !== null) {
          list.push({ start: on.index, end: on.index + on[1].length, type: "command" });
          off = false;
        } else if (trimmed.length > 0) {
          addTrimmed(list, line, "excluded");
        }
        continue;
      }
      // --- verbatim (!!block body) ----------------------------------------
      if (verbatim) {
        if (blockEnd !== null) {
          if (trimmed === blockEnd) {
            addTrimmed(list, line, "block-end");
            verbatim = false;
            blockEnd = null;
          } else if (trimmed.length > 0) {
            addTrimmed(list, line, "verbatim");
          }
        } else if (trimmed.length === 0) {
          verbatim = false;   // blank line ends the unmarked form
        } else {
          addTrimmed(list, line, "verbatim");
        }
        continue;
      }
      const offM = OFF_LINE.exec(line);
      if (offM !== null) {
        list.push({ start: offM.index, end: offM.index + offM[1].length, type: "command" });
        off = true;
        inMetaBlock = false;
        continue;
      }

      // --- inside a ,, block ----------------------------------------------
      if (inMetaBlock) {
        if (trimmed === ",,") {
          addTrimmed(list, line, "meta-delim");
          inMetaBlock = false;
        } else if (trimmed.length > 0) {
          if (SIGNIFICANT.test(line) && startsWithOperator(line)) {
            emitLine(list, line, "meta_start", true);
          } else {
            addTrimmed(list, line, "meta-text");
          }
        }
        continue;
      }
      if (trimmed.length === 0) continue;

      // --- a standalone `,,` opens a block for the previous triple --------
      if (trimmed === ",,") {
        addTrimmed(list, line, "meta-delim");
        inMetaBlock = true;
        continue;
      }

      // --- `,, ..mr.. mo` continues the previous logical line -------------
      const mc = META_CONTINUATION.exec(line);
      if (mc !== null) {
        // The consumed match is leading ws + the 2-char delimiter (the meta
        // pair itself sits in a lookahead), so the group ends the match.
        const gEnd = mc.index + mc[0].length;
        const gStart = gEnd - 2;
        list.push({ start: gStart, end: gEnd, type: mc[1] !== undefined ? "meta-delim" : "meta-separator" });
        const masked = line.substring(0, gEnd).replace(/[^ \t]/g, " ") + line.substring(gEnd);
        emitLine(list, masked, "meta_start", false);
        continue;
      }

      // --- `!!block` alone on a line = a SUBJECT opener --------------------
      const bo = findFrom(BLOCK_OPENER_SRC, line, 0);
      if (bo !== null && line.substring(0, bo.index).trim().length === 0) {
        emitBlockOpener(list, bo);
        verbatim = true;
        blockEnd = bo[3] !== undefined ? bo[3] : null;
        continue;
      }

      // --- the gate ---------------------------------------------------------
      if (!GATE.test(line)) continue;   // NotATriple: no spans at all

      const st = emitLine(list, line, "subject", false);
      if (st.opensBlock) {
        verbatim = true;
        blockEnd = st.blockEnd;
      } else if (st.opensMeta) {
        inMetaBlock = true;
      }
    }
    return toks;
  }

  // --- HTML emission --------------------------------------------------------

  // `?query` / `#fragment` render as a nested command-param span.
  function commandSpan(text, out) {
    let idx = -1;
    for (let i = 0; i < text.length; i++) {
      const c = text.charAt(i);
      if (c === "?" || c === "#") { idx = i; break; }
    }
    if (idx >= 0) {
      out.push('<span class="ddot-command">' + escapeHtml(text.substring(0, idx))
        + '<span class="ddot-command-param">' + escapeHtml(text.substring(idx))
        + "</span></span>");
    } else {
      out.push('<span class="ddot-command">' + escapeHtml(text) + "</span>");
    }
  }

  // Slot content with nested in-text command spans (Highlight Spec D6).
  function slotInner(text, out) {
    const re = new RegExp(NESTED_CMD_SRC, "g");
    let pos = 0;
    let m;
    while ((m = re.exec(text)) !== null) {
      out.push(escapeHtml(text.substring(pos, m.index)));
      commandSpan(m[0], out);
      pos = m.index + m[0].length;
    }
    out.push(escapeHtml(text.substring(pos)));
  }

  function renderToken(text, type, out) {
    if (type === "command") {
      commandSpan(text, out);
    } else if (SLOT_TOKENS.has(type)) {
      out.push('<span class="' + TOKEN_CLASS[type] + '">');
      slotInner(text, out);
      out.push("</span>");
    } else {
      out.push('<span class="' + TOKEN_CLASS[type] + '">' + escapeHtml(text) + "</span>");
    }
  }

  function renderLine(line, toks, out) {
    let pos = 0;
    for (const t of toks) {
      if (t.start > pos) out.push(escapeHtml(line.substring(pos, t.start)));
      renderToken(line.substring(t.start, t.end), t.type, out);
      pos = t.end;
    }
    if (pos < line.length) out.push(escapeHtml(line.substring(pos)));
  }

  function renderBody(raw) {
    // Strip a single trailing newline that the Markdown lib often leaves on
    // fence bodies; preserve internal blank lines.
    const body = raw.endsWith("\n") ? raw.substring(0, raw.length - 1) : raw;
    const lines = body.split("\n");
    const tokens = tokenize(lines);
    const out = [];
    for (let i = 0; i < lines.length; i++) {
      if (i > 0) out.push("\n");
      renderLine(lines[i], tokens[i], out);
    }
    return out.join("");
  }

  // --- DOM plumbing -----------------------------------------------------------

  // Match `<code class="language-ddot…">` covering the three accepted info
  // strings (ddot, ddot.it, ddotit).
  function isDdotCodeBlock(el) {
    if (el.tagName !== "CODE") return false;
    for (const c of el.classList) {
      const lower = c.toLowerCase();
      if (lower === "language-ddot" || lower === "language-ddot.it" || lower === "language-ddotit") return true;
    }
    return false;
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

  // Expose the pure renderer for the Node test harness
  // (scripts/test-preview-js.mjs) without changing browser behaviour.
  if (typeof globalThis !== "undefined") {
    globalThis.ddotPreview = { renderBody: renderBody };
  }

  if (typeof document !== "undefined") {
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", start);
    } else {
      start();
    }
  }
})();
