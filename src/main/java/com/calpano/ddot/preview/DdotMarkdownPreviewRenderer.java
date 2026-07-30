package com.calpano.ddot.preview;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Role-classed body HTML for ddot fence content. Lives here because two
 * things need it:
 *
 * <ul>
 *   <li>The cross-implementation golden corpus at
 *       {@code ../ddot.it/test-data/cases/} — every case has
 *       {@code expected.body.html} that this renderer must produce
 *       byte-for-byte. The Ruby renderer ({@code ddot-render.rb}) is
 *       canonical; this Java is a mirror.</li>
 *   <li>{@code DdotPreviewBrowserExtension}'s shipped JS — its line-by-line
 *       role-assignment logic is a port of {@link #body}, and we keep this
 *       Java implementation as the executable spec the goldens lock down.
 *       Any change here must land in {@code ddot-preview.js} too.</li>
 * </ul>
 *
 * <p>The line/slot model is a faithful port of the corpus-pinned positional
 * tokenizer {@link com.calpano.ddot.it.token.DdotTokenizer} (which
 * mirrors the TextMate grammar): tokenize each line into
 * {@code {start, end, token}} spans, then wrap each span in a role-classed
 * {@code <span>}. Normative rules: {@code site/spec/ddot-it-parse.adoc}
 * (token counting, the gate, off/on, {@code !!block}) and
 * {@code site/spec/ddot-it-highlight.adoc} (roles D1–D9).
 *
 * <p>Pure functions, no IDE dependencies — usable from plain unit tests.
 */
public final class DdotMarkdownPreviewRenderer {

    private DdotMarkdownPreviewRenderer() {}

    // --- counted tokens (Parse Spec, "Token Sequences") --------------------
    // Runs of EXACTLY n, neither preceded nor followed by the same character.
    /** WS per the Parse Spec: TAB plus any Unicode space separator (Zs). */
    private static final String WS = "[\\t\\p{Zs}]";
    private static final String DT2_SRC  = "(?<!\\.)\\.{2}(?!\\.)";
    private static final String QUAD_SRC = "(?<!\\.)(?:\\.{4}|\\.{2}" + WS + "+\\.{2})(?!\\.)";
    private static final String CM2_SRC  = "(?<!,),,(?!,)";
    private static final String SC2_SRC  = "(?<!;);;(?!;)";

    // A command is recognised by SHAPE (Highlight Spec D6): `!!name`,
    // `ddot.it/name`, `http(s)://ddot.it/name` with a non-empty name. `!!` is
    // an EM2 (exactly two `!`), so `!!!off` is not a command prefix. `?` and
    // `#` do not terminate a command; they introduce its query/fragment.
    private static final String CMD_PREFIX_SRC = "(?:(?:https?://)?ddot\\.it/|(?<!!)!!(?!!))";
    private static final String CMD_SRC =
            CMD_PREFIX_SRC + "[^\\s?#]+(?:\\?[^\\s#]*)?(?:#\\S*)?";

    private static final Pattern CMD_ONLY = Pattern.compile("^" + CMD_SRC + "$");
    /** A command nested in field text: preceded by start-of-slot or whitespace. */
    private static final Pattern NESTED_CMD = Pattern.compile("(?<!\\S)" + CMD_SRC);

    // `!!off` / `!!on` are recognised ANYWHERE on a line (Parse Spec
    // "pre-parse-recognition") — commonly inside a host comment
    // (`<!-- ddot.it/off -->`, `# !!on`). `(?![^\s?#])` pins the exact NAME,
    // so `!!office` is not `!!off`.
    private static final Pattern OFF_LINE =
            Pattern.compile("(" + CMD_PREFIX_SRC + "off)(?![^\\s?#])");
    private static final Pattern ON_LINE =
            Pattern.compile("(" + CMD_PREFIX_SRC + "on)(?![^\\s?#])");

    /** A `!!block` opener must END its physical line (block-as-field). */
    private static final Pattern BLOCK_OPENER =
            Pattern.compile("(" + CMD_PREFIX_SRC + "block)(?:(\\?end=)(\\S+))?" + WS + "*$");

    /**
     * A line-initial `,,` or `;;` followed by a meta pair continues the
     * previous logical line as metadata (Parse Spec "block-continuation").
     */
    private static final Pattern META_CONTINUATION = Pattern.compile(
            "^" + WS + "*(?:(" + CM2_SRC + ")|(" + SC2_SRC + "))(?=" + WS + "+" + DT2_SRC + ")");

    /** QUAD before DT2 so `.. ..` is ONE operator, not two. */
    private static final Pattern SIGNIFICANT =
            Pattern.compile(QUAD_SRC + "|" + DT2_SRC + "|" + CM2_SRC + "|" + SC2_SRC);

    /**
     * THE GATE (How-To-Highlight D5): a line is ddot.it only when it carries
     * a complete operator skeleton — two DT2, or one DT4 / `.. ..`. A lone
     * `..` or an ellipsis in prose stays entirely plain (23-not-a-triple).
     */
    private static final Pattern GATE = Pattern.compile(
            "(?:(?<!\\.)\\.{4}(?!\\.)|(?<!\\.)\\.{2}(?!\\.).*(?<!\\.)\\.{2}(?!\\.))");

    // --- token type → CSS class --------------------------------------------
    private static final Map<String, String> TOKEN_CLASS = Map.ofEntries(
            Map.entry("subject",        "ddot-subject"),
            Map.entry("relation",       "ddot-predicate"),
            Map.entry("object",         "ddot-object"),
            Map.entry("doubledot",      "ddot-sep"),
            Map.entry("meta-doubledot", "ddot-sep"),
            Map.entry("meta-delim",     "ddot-meta-sep"),
            Map.entry("meta-separator", "ddot-meta-sep"),
            Map.entry("meta-relation",  "ddot-meta-relation"),
            Map.entry("meta-object",    "ddot-meta-object"),
            Map.entry("meta-text",      "ddot-meta-text"),
            Map.entry("command",        "ddot-command"),
            Map.entry("command-param",  "ddot-command-param"),
            Map.entry("block-end",      "ddot-block-end"),
            Map.entry("excluded",       "ddot-inactive"),
            Map.entry("verbatim",       "ddot-verbatim"));

    /** Slots that may carry a nested in-text command span (Highlight Spec D6). */
    private static final Set<String> SLOT_TOKENS = Set.of(
            "subject", "relation", "object", "meta-relation", "meta-object", "meta-text");

    /** One positional token on a line: [start, end) and its type. */
    private record Tok(int start, int end, String type) {}

    /** Slot phases within one logical line. */
    private enum Phase { SUBJECT, RELATION, OBJECT, META_START, META_REL, META_OBJ }

    private static final class BlockState {
        boolean opensBlock, opensMetaBlock;
        String blockEnd;
    }

    /**
     * Body-only HTML — role-classed span markup, no {@code <style>} or
     * {@code <pre>} wrapper. Mirrors {@code DdotIt::Render.body} in
     * {@code ddot-render.rb}; the cross-implementation golden corpus at
     * {@code ../ddot.it/test-data/cases/} asserts byte equivalence.
     */
    public static String body(@NotNull String raw) {
        // Strip a single trailing newline that the Markdown lib often leaves on
        // fence bodies; preserve internal blank lines.
        String stripped = raw.endsWith("\n") ? raw.substring(0, raw.length() - 1) : raw;
        String[] lines = stripped.split("\n", -1);
        List<List<Tok>> tokens = tokenize(lines);

        StringBuilder out = new StringBuilder(raw.length() * 4);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            renderLine(lines[i], tokens.get(i), out);
        }
        return out.toString();
    }

    // --- tokenizer: port of DdotTokenizer#tokenize --------------------------

    private static List<List<Tok>> tokenize(String[] lines) {
        List<List<Tok>> toks = new ArrayList<>(lines.length);
        for (int i = 0; i < lines.length; i++) toks.add(new ArrayList<>());

        boolean off = false;          // inside !!off … !!on
        boolean verbatim = false;     // inside a !!block body
        String blockEnd = null;       // custom ?end= marker, null = blank-line form
        boolean inMetaBlock = false;  // inside a ,, … ,, block

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = trimWs(line);
            List<Tok> list = toks.get(i);

            // --- excluded region: inactive, NO role spans ------------------
            if (off) {
                Matcher on = ON_LINE.matcher(line);
                if (on.find()) { list.add(new Tok(on.start(1), on.end(1), "command")); off = false; }
                else if (!trimmed.isEmpty()) addTrimmed(list, line, "excluded");
                continue;
            }
            // --- verbatim (!!block body) ------------------------------------
            if (verbatim) {
                if (blockEnd != null) {
                    if (trimmed.equals(blockEnd)) {
                        addTrimmed(list, line, "block-end");
                        verbatim = false; blockEnd = null;
                    } else if (!trimmed.isEmpty()) {
                        addTrimmed(list, line, "verbatim");
                    }
                } else if (trimmed.isEmpty()) {
                    verbatim = false;      // blank line ends the unmarked form
                } else {
                    addTrimmed(list, line, "verbatim");
                }
                continue;
            }
            Matcher offM = OFF_LINE.matcher(line);
            if (offM.find()) {
                list.add(new Tok(offM.start(1), offM.end(1), "command"));
                off = true; inMetaBlock = false;
                continue;
            }

            // --- inside a ,, block ------------------------------------------
            if (inMetaBlock) {
                if (trimmed.equals(",,")) { addTrimmed(list, line, "meta-delim"); inMetaBlock = false; }
                else if (!trimmed.isEmpty()) {
                    if (SIGNIFICANT.matcher(line).find() && startsWithOperator(line)) {
                        emitLine(list, line, Phase.META_START, true);
                    } else {
                        addTrimmed(list, line, "meta-text");
                    }
                }
                continue;
            }
            if (trimmed.isEmpty()) continue;

            // --- a standalone `,,` opens a block for the previous triple ----
            if (trimmed.equals(",,")) { addTrimmed(list, line, "meta-delim"); inMetaBlock = true; continue; }

            // --- `,, ..mr.. mo` continues the previous logical line ---------
            Matcher mc = META_CONTINUATION.matcher(line);
            if (mc.find()) {
                int g = mc.group(1) != null ? 1 : 2;
                list.add(new Tok(mc.start(g), mc.end(g), g == 1 ? "meta-delim" : "meta-separator"));
                String masked = line.substring(0, mc.end(g)).replaceAll("[^ \\t]", " ")
                        + line.substring(mc.end(g));
                emitLine(list, masked, Phase.META_START, false);
                continue;
            }

            // --- `!!block` alone on a line = a SUBJECT opener ----------------
            Matcher bo = BLOCK_OPENER.matcher(line);
            if (bo.find() && trimWs(line.substring(0, bo.start())).isEmpty()) {
                emitBlockOpener(list, bo);
                verbatim = true; blockEnd = bo.group(3);
                continue;
            }

            // --- the gate ----------------------------------------------------
            if (!GATE.matcher(line).find()) continue;   // NotATriple: no spans

            BlockState st = emitLine(list, line, Phase.SUBJECT, false);
            if (st.opensBlock) { verbatim = true; blockEnd = st.blockEnd; }
            else if (st.opensMetaBlock) inMetaBlock = true;
        }
        return toks;
    }

    private static boolean startsWithOperator(String line) {
        Matcher m = SIGNIFICANT.matcher(line);
        return m.find() && trimWs(line.substring(0, m.start())).isEmpty();
    }

    /**
     * A {@code !!block} opener is valid ONLY in a value position. The spec
     * names four (Parse Spec "block-as-field"): Subject, Object, MetaObject,
     * and the free meta text after {@code ,,} ({@link Phase#META_START}).
     * Relation and MetaRelation hold names, so a block there stays in-text.
     */
    private static boolean opensBlockIn(Phase phase) {
        return phase == Phase.SUBJECT || phase == Phase.OBJECT
                || phase == Phase.META_OBJ || phase == Phase.META_START;
    }

    /**
     * Emit one line as a sequence of slots and operators, starting in
     * {@code phase}. Mirrors the parse automaton: the object may contain
     * {@code ..}, {@code ;;} separates inline meta pairs only, and a
     * {@code !!block} that ends the line opens a verbatim region.
     */
    private static BlockState emitLine(List<Tok> list, String line, Phase phase, boolean inBlock) {
        BlockState st = new BlockState();
        Matcher m = SIGNIFICANT.matcher(line);
        int last = 0;

        while (m.find()) {
            String sep = m.group();
            boolean isQuad = sep.length() == 4 || sep.indexOf(' ') >= 0 || sep.indexOf('\t') >= 0;
            boolean isComma = sep.equals(",,");
            boolean isSemi = sep.equals(";;");
            int sepStart = m.start(), sepEnd = m.end();

            // Once in an object-ish phase, `..` is content, not a separator.
            if (!isComma && !isSemi && (phase == Phase.OBJECT || phase == Phase.META_OBJ)) continue;
            // `;;` separates inline meta pairs ONLY; inside a `,,` block it is
            // ordinary content (28-semicolon-in-meta-block).
            if (isSemi && (inBlock || phase != Phase.META_OBJ)) continue;
            if (isComma && phase != Phase.OBJECT) continue;

            // The relation-closing operator is always a plain `..`, never the
            // untyped `.. ..` form — otherwise an object starting with `..`
            // (a relative path) merges with the closing marker (20-object-dotdot).
            if (isQuad && sep.length() != 4
                    && (phase == Phase.RELATION || phase == Phase.META_REL)) {
                isQuad = false;
                sep = line.substring(sepStart, sepStart + 2);
                sepEnd = sepStart + 2;
            }

            // A `!!block` ending the line makes the pending slot a block opener —
            // but only when that slot is a value position (block-as-field).
            if (opensBlockIn(phase)) {
                Matcher bo = BLOCK_OPENER.matcher(line);
                if (bo.find(last) && trimWs(line.substring(last, bo.start())).isEmpty()
                        && bo.start() < m.start()) {
                    emitBlockOpener(list, bo);
                    st.opensBlock = true; st.blockEnd = bo.group(3);
                    return st;
                }
            }

            emitSlot(list, line, last, sepStart, phase);
            String sepType = isComma ? "meta-delim" : isSemi ? "meta-separator"
                    : (phase == Phase.META_START || phase == Phase.META_REL || phase == Phase.META_OBJ || inBlock)
                      ? "meta-doubledot" : "doubledot";
            list.add(new Tok(sepStart, sepEnd, sepType));
            last = sepEnd;

            phase = switch (phase) {
                case SUBJECT -> isQuad ? Phase.OBJECT : Phase.RELATION;
                case RELATION -> Phase.OBJECT;
                case OBJECT -> isComma ? Phase.META_START : Phase.OBJECT;
                // The untyped meta form `,, .... obj` skips the meta relation.
                case META_START -> isQuad ? Phase.META_OBJ : Phase.META_REL;
                case META_REL -> Phase.META_OBJ;
                case META_OBJ -> isSemi ? Phase.META_START : Phase.META_OBJ;
            };
        }

        // Trailing slot, or a block opener with no separator after it. The
        // meta-text position lands here: `… ,, !!block` leaves phase
        // META_START with nothing but the opener left on the line.
        if (opensBlockIn(phase)) {
            Matcher bo = BLOCK_OPENER.matcher(line);
            if (bo.find(last) && trimWs(line.substring(last, bo.start())).isEmpty()) {
                emitBlockOpener(list, bo);
                st.opensBlock = true; st.blockEnd = bo.group(3);
                return st;
            }
        }
        if (trimWs(line.substring(last)).isEmpty() && phase == Phase.META_START) {
            st.opensMetaBlock = true;   // trailing `,,` opened a block
        } else {
            emitSlot(list, line, last, line.length(), phase);
        }
        return st;
    }

    private static void emitBlockOpener(List<Tok> list, Matcher bo) {
        list.add(new Tok(bo.start(1), bo.end(1), "command"));
        if (bo.group(2) != null) {
            list.add(new Tok(bo.start(2), bo.end(2), "command-param"));
            list.add(new Tok(bo.start(3), bo.end(3), "block-end"));
        }
    }

    private static void emitSlot(List<Tok> list, String line, int lo, int hi, Phase phase) {
        int s = lo, e = hi;
        while (s < e && isWs(line.charAt(s))) s++;
        while (e > s && isWs(line.charAt(e - 1))) e--;
        if (s >= e) return;

        String text = line.substring(s, e);
        String type;
        if (CMD_ONLY.matcher(text).matches()) {
            type = "command";   // a slot whose whole text is a command IS a command
        } else {
            type = switch (phase) {
                case SUBJECT -> "subject";
                case RELATION -> "relation";
                case OBJECT -> "object";
                case META_REL -> "meta-relation";
                case META_OBJ -> "meta-object";
                case META_START -> "meta-text";   // text after `,,` with no `..`
            };
        }
        list.add(new Tok(s, e, type));
    }

    /**
     * True for what {@link String#trim()} strips plus every Unicode space
     * separator. {@code Character.isWhitespace} rejects NBSP and NARROW NBSP,
     * which the spec.s `Zs` class includes.
     */
    private static boolean isWs(char c) {
        return c <= ' ' || Character.getType(c) == Character.SPACE_SEPARATOR;
    }

    /** {@link String#trim()} over the spec's WS class. */
    private static String trimWs(String s) {
        int lo = 0, hi = s.length();
        while (lo < hi && isWs(s.charAt(lo))) lo++;
        while (hi > lo && isWs(s.charAt(hi - 1))) hi--;
        return s.substring(lo, hi);
    }

    private static void addTrimmed(List<Tok> list, String line, String type) {
        int s = 0, e = line.length();
        while (s < e && isWs(line.charAt(s))) s++;
        while (e > s && isWs(line.charAt(e - 1))) e--;
        if (s < e) list.add(new Tok(s, e, type));
    }

    // --- HTML emission -------------------------------------------------------

    private static void renderLine(String line, List<Tok> toks, StringBuilder out) {
        int pos = 0;
        for (Tok t : toks) {
            if (t.start() > pos) out.append(escape(line.substring(pos, t.start())));
            renderToken(line.substring(t.start(), t.end()), t.type(), out);
            pos = t.end();
        }
        if (pos < line.length()) out.append(escape(line.substring(pos)));
    }

    private static void renderToken(String text, String type, StringBuilder out) {
        if (type.equals("command")) {
            commandSpan(text, out);
        } else if (SLOT_TOKENS.contains(type)) {
            out.append("<span class=\"").append(TOKEN_CLASS.get(type)).append("\">");
            slotInner(text, out);
            out.append("</span>");
        } else {
            out.append("<span class=\"").append(TOKEN_CLASS.get(type)).append("\">")
               .append(escape(text)).append("</span>");
        }
    }

    /** {@code ?query} / {@code #fragment} render as a nested command-param span. */
    private static void commandSpan(String text, StringBuilder out) {
        int idx = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '?' || c == '#') { idx = i; break; }
        }
        if (idx >= 0) {
            out.append("<span class=\"ddot-command\">").append(escape(text.substring(0, idx)))
               .append("<span class=\"ddot-command-param\">").append(escape(text.substring(idx)))
               .append("</span></span>");
        } else {
            out.append("<span class=\"ddot-command\">").append(escape(text)).append("</span>");
        }
    }

    /** Slot content with nested in-text command spans (Highlight Spec D6). */
    private static void slotInner(String text, StringBuilder out) {
        Matcher m = NESTED_CMD.matcher(text);
        int pos = 0;
        while (m.find()) {
            out.append(escape(text.substring(pos, m.start())));
            commandSpan(m.group(), out);
            pos = m.end();
        }
        out.append(escape(text.substring(pos)));
    }

    private static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '&' -> b.append("&amp;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
