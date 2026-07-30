package com.calpano.ddot.completion;

import com.calpano.ddot.highlighting.DdotTokenizer;
import com.calpano.ddot.psi.DdotRole;
import com.intellij.openapi.editor.Document;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure cursor-context helpers, shared between the completion contributor and the
 * auto-popup listener. No IDE state beyond {@link Document}.
 *
 * <p>Slot detection implements the Autocomplete Specification's "cursor state"
 * rule: run the parse automaton over the line prefix and read the slot off the
 * state it reaches. There is deliberately no second grammar — the automaton is
 * prefix-driven, so a half-written line lands in a sensible state on its own
 * (typing {@code Dir} reaches Subject), and it is defined over real DT2/DT4
 * tokens, so a stray {@code ...} cannot be miscounted as a separator.
 *
 * <p>The automaton starts in the state the <em>document</em> is in at the
 * beginning of the caret line ({@link #documentStateAt}): an ordinary line
 * starts at {@code StartOfLine}, a line inside a {@code ,,} block starts in the
 * matching block state, and a line inside an excluded ({@code !!off}…{@code !!on})
 * or verbatim ({@code !!block} body) region is inert — no completion at all.
 */
final class DdotContext {
    private DdotContext() {}

    // Runs of EXACTLY n, neither preceded nor followed by the same character.
    // DT4 before DT2 so `....` is ONE token. The spaced untyped form `.. ..`
    // is deliberately TWO DT2 tokens — exactly how the parse automaton reads
    // it (RelationStart + DT2 → ObjectStart, "the other spelling of QuadDot").
    private static final String DT2_SRC = "(?<!\\.)\\.{2}(?!\\.)";
    private static final String DT4_SRC = "(?<!\\.)\\.{4}(?!\\.)";
    private static final String CM2_SRC = "(?<!,),,(?!,)";
    private static final String SC2_SRC = "(?<!;);;(?!;)";

    private static final Pattern SIGNIFICANT =
            Pattern.compile(DT4_SRC + "|" + DT2_SRC + "|" + CM2_SRC + "|" + SC2_SRC);

    /** A complete operator skeleton: two DT2, or one DT4 / {@code .. ..}. */
    private static final Pattern GATE = Pattern.compile(
            "(?:(?<!\\.)\\.{4}(?!\\.)|(?<!\\.)\\.{2}(?!\\.).*(?<!\\.)\\.{2}(?!\\.))");

    /**
     * The document's pre-parse/block state at the start of a line, derived from
     * the text of all preceding lines.
     */
    enum DocState {
        /** An ordinary line: the automaton starts at {@code StartOfLine}. */
        NORMAL,
        /** Inside a {@code ,,} block that has not committed to triples or text yet. */
        BLOCK_FIRST_LINE,
        /** Inside a {@code ,,} block that committed to triples. */
        BLOCK_TRIPLE_LINE,
        /** Inside a {@code ,,} block that committed to text. */
        BLOCK_TEXT_LINE,
        /** Inside a {@code !!off} … {@code !!on} excluded region — inert. */
        EXCLUDED,
        /** Inside a {@code !!block} verbatim body — inert. */
        VERBATIM
    }

    /** The parse automaton's states (Parse Specification, "Parse State Automaton"). */
    enum State {
        START_OF_LINE, MAYBE_BLOCK_OPEN, SUBJECT, RELATION_START, RELATION,
        OBJECT_START, TRIPLE_OBJECT,
        META_START, META_RELATION_INLINE, META_OBJECT_INLINE, META_NEXT_INLINE, META_TEXT_INLINE,
        BLOCK_FIRST_LINE, BLOCK_TRIPLE_LINE, META_RELATION_IN_BLOCK, META_OBJECT_IN_BLOCK,
        BLOCK_TEXT_LINE, META_TEXT_BLOCK_LINE, BLOCK_END,
        NOT_A_TRIPLE,
        /** Excluded/verbatim region: nothing is parsed, nothing is completed. */
        INERT
    }

    /**
     * Token events. {@code EM2} ({@code !!}) is grouped with {@code TX} in every
     * automaton transition, so it needs no event of its own; {@code WS} is a
     * self-loop in every state and is skipped entirely.
     */
    private enum Ev { TX, DT2, DT4, CM2, SC2 }

    /** One transition of the parse automaton (parse spec, parts 1–3). */
    private static State step(State s, Ev e) {
        return switch (s) {
            case START_OF_LINE -> switch (e) {
                case TX, SC2 -> State.SUBJECT;
                case CM2 -> State.MAYBE_BLOCK_OPEN;
                case DT2 -> State.RELATION_START;
                case DT4 -> State.OBJECT_START;
            };
            // A line-initial `,,` may still turn out to be subject text.
            case MAYBE_BLOCK_OPEN -> switch (e) {
                case TX, SC2, CM2 -> State.SUBJECT;
                case DT2 -> State.RELATION_START;
                case DT4 -> State.OBJECT_START;
            };
            // A `,,` mid-subject is content, not a meta delimiter.
            case SUBJECT -> switch (e) {
                case TX, SC2, CM2 -> State.SUBJECT;
                case DT2 -> State.RELATION_START;
                case DT4 -> State.OBJECT_START;
            };
            case RELATION_START -> switch (e) {
                case TX, SC2, CM2 -> State.RELATION;
                case DT2 -> State.OBJECT_START;   // `.. ..` — the other spelling of QuadDot
                case DT4 -> State.NOT_A_TRIPLE;
            };
            case RELATION -> switch (e) {
                case TX, SC2, CM2 -> State.RELATION;
                case DT2 -> State.OBJECT_START;
                case DT4 -> State.NOT_A_TRIPLE;
            };
            // An empty object followed by `,,` is not a triple.
            case OBJECT_START -> switch (e) {
                case TX, SC2, DT2, DT4 -> State.TRIPLE_OBJECT;
                case CM2 -> State.NOT_A_TRIPLE;
            };
            case TRIPLE_OBJECT -> e == Ev.CM2 ? State.META_START : State.TRIPLE_OBJECT;
            case META_START -> switch (e) {
                case DT2 -> State.META_RELATION_INLINE;
                case DT4 -> State.META_OBJECT_INLINE;   // implicit MetaRelation
                case TX, SC2, CM2 -> State.META_TEXT_INLINE;
            };
            case META_RELATION_INLINE -> switch (e) {
                case TX, SC2, CM2 -> State.META_RELATION_INLINE;
                case DT2 -> State.META_OBJECT_INLINE;
                case DT4 -> State.NOT_A_TRIPLE;
            };
            case META_OBJECT_INLINE -> e == Ev.SC2 ? State.META_NEXT_INLINE : State.META_OBJECT_INLINE;
            case META_NEXT_INLINE -> switch (e) {
                case DT2 -> State.META_RELATION_INLINE;
                case DT4 -> State.META_OBJECT_INLINE;
                case TX, SC2, CM2 -> State.NOT_A_TRIPLE;
            };
            case META_TEXT_INLINE -> State.META_TEXT_INLINE;
            case BLOCK_FIRST_LINE -> switch (e) {
                case DT2 -> State.META_RELATION_IN_BLOCK;   // commit to triples
                case DT4 -> State.META_OBJECT_IN_BLOCK;
                case TX, SC2 -> State.META_TEXT_BLOCK_LINE; // commit to text
                case CM2 -> State.BLOCK_END;                // empty block
            };
            case BLOCK_TRIPLE_LINE -> switch (e) {
                case DT2 -> State.META_RELATION_IN_BLOCK;
                case DT4 -> State.META_OBJECT_IN_BLOCK;
                case CM2 -> State.BLOCK_END;
                case TX, SC2 -> State.NOT_A_TRIPLE;         // no text in a triple block
            };
            case META_RELATION_IN_BLOCK -> switch (e) {
                case TX, SC2, CM2 -> State.META_RELATION_IN_BLOCK;
                case DT2 -> State.META_OBJECT_IN_BLOCK;
                case DT4 -> State.NOT_A_TRIPLE;
            };
            case META_OBJECT_IN_BLOCK -> State.META_OBJECT_IN_BLOCK;
            case BLOCK_TEXT_LINE -> switch (e) {
                case TX, SC2 -> State.META_TEXT_BLOCK_LINE;
                case CM2 -> State.BLOCK_END;
                case DT2, DT4 -> State.NOT_A_TRIPLE;        // no triples in a text block
            };
            case META_TEXT_BLOCK_LINE -> State.META_TEXT_BLOCK_LINE;
            case BLOCK_END -> State.NOT_A_TRIPLE;           // only WS/NL may follow the closing `,,`
            case NOT_A_TRIPLE, INERT -> s;
        };
    }

    /**
     * Run the automaton over the caret line's prefix, starting in the state the
     * document is in at the start of the line.
     */
    static @NotNull State stateAtCursor(@NotNull String beforeCursor, @NotNull DocState docState) {
        State state = switch (docState) {
            case NORMAL -> State.START_OF_LINE;
            case BLOCK_FIRST_LINE -> State.BLOCK_FIRST_LINE;
            case BLOCK_TRIPLE_LINE -> State.BLOCK_TRIPLE_LINE;
            case BLOCK_TEXT_LINE -> State.BLOCK_TEXT_LINE;
            case EXCLUDED, VERBATIM -> State.INERT;
        };
        if (state == State.INERT) return state;

        int last = 0;
        Matcher m = SIGNIFICANT.matcher(beforeCursor);
        while (m.find()) {
            if (!beforeCursor.substring(last, m.start()).isBlank()) state = step(state, Ev.TX);
            state = step(state, eventOf(m.group()));
            last = m.end();
        }
        if (!beforeCursor.substring(last).isBlank()) state = step(state, Ev.TX);
        return state;
    }

    private static Ev eventOf(String token) {
        if (token.equals(",,")) return Ev.CM2;
        if (token.equals(";;")) return Ev.SC2;
        return token.length() == 4 ? Ev.DT4 : Ev.DT2;
    }

    /**
     * The slot queried in {@code state}, or {@code null} where completion is not
     * offered (meta text, a line that cannot become a triple, inert regions).
     * Mirrors the Autocomplete Specification's state → query table.
     */
    static @Nullable DdotRole roleOf(@NotNull State state) {
        return switch (state) {
            case START_OF_LINE, SUBJECT, MAYBE_BLOCK_OPEN -> DdotRole.SUBJECT;
            case RELATION_START, RELATION -> DdotRole.PREDICATE;
            case OBJECT_START, TRIPLE_OBJECT -> DdotRole.OBJECT;
            case META_START, META_RELATION_INLINE, META_NEXT_INLINE,
                 BLOCK_FIRST_LINE, BLOCK_TRIPLE_LINE, META_RELATION_IN_BLOCK -> DdotRole.META_RELATION;
            case META_OBJECT_INLINE, META_OBJECT_IN_BLOCK -> DdotRole.META_OBJECT;
            case META_TEXT_INLINE, META_TEXT_BLOCK_LINE, BLOCK_TEXT_LINE,
                 BLOCK_END, NOT_A_TRIPLE, INERT -> null;
        };
    }

    /** Convenience: slot for a line prefix on an ordinary ({@code NORMAL}) line. */
    static @Nullable DdotRole slotAtCursor(@NotNull String beforeCursor) {
        return roleOf(stateAtCursor(beforeCursor, DocState.NORMAL));
    }

    /** Slot for a line prefix given the document state at the start of the line. */
    static @Nullable DdotRole slotAtCursor(@NotNull String beforeCursor, @NotNull DocState docState) {
        return roleOf(stateAtCursor(beforeCursor, docState));
    }

    /**
     * Probe line used by {@link #documentStateAt}: plain text that no author
     * would use as a {@code ?end=} marker, containing no ddot operators.
     */
    private static final String PROBE = "⁣ddot-doc-state-probe⁣";

    /**
     * The document state at the start of the line that FOLLOWS {@code precedingText}.
     *
     * <p>Implemented by running the canonical {@link DdotTokenizer} over the
     * preceding text plus a probe line and reading how the probe is classified:
     * {@code excluded} → inside {@code !!off}…{@code !!on}; {@code verbatim} →
     * inside a {@code !!block} body; {@code meta-text} → inside a {@code ,,}
     * block (refined into first/triple/text line by looking at the block's
     * previous lines); anything else → an ordinary line.
     */
    static @NotNull DocState documentStateAt(@NotNull String precedingText) {
        if (precedingText.isBlank()) return DocState.NORMAL;
        String text = precedingText.endsWith("\n") ? precedingText : precedingText + "\n";
        int probeLine = countNewlines(text);
        List<DdotTokenizer.Token> tokens = DdotTokenizer.tokenize(text + PROBE);

        String probeToken = null;
        for (DdotTokenizer.Token t : tokens) {
            if (t.line() == probeLine) {
                probeToken = t.token();
                break;
            }
        }
        if (probeToken == null) return DocState.NORMAL;
        return switch (probeToken) {
            case "excluded" -> DocState.EXCLUDED;
            case "verbatim", "block-end" -> DocState.VERBATIM;
            case "meta-text" -> blockLineState(tokens, probeLine);
            default -> DocState.NORMAL;
        };
    }

    /**
     * Which block state a fresh line inside a {@code ,,} block is in: right after
     * the opener → {@code BlockFirstLine}; after a {@code ..mr.. mo} line →
     * {@code BlockTripleLine}; after a text line → {@code BlockTextLine}.
     */
    private static DocState blockLineState(List<DdotTokenizer.Token> tokens, int probeLine) {
        for (int line = probeLine - 1; line >= 0; line--) {
            DdotTokenizer.Token lastOfLine = null;
            boolean hasMetaText = false;
            for (DdotTokenizer.Token t : tokens) {
                if (t.line() != line) continue;
                lastOfLine = t;
                if (t.token().equals("meta-text")) hasMetaText = true;
            }
            if (lastOfLine == null) continue;   // blank line inside the block
            // The opener: a standalone `,,`, or a triple line ending in `,,`.
            if (lastOfLine.token().equals("meta-delim") && lastOfLine.text().equals(",,")) {
                return DocState.BLOCK_FIRST_LINE;
            }
            return hasMetaText ? DocState.BLOCK_TEXT_LINE : DocState.BLOCK_TRIPLE_LINE;
        }
        return DocState.BLOCK_FIRST_LINE;
    }

    private static int countNewlines(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') n++;
        }
        return n;
    }

    /**
     * True when an earlier line (since the most recent blank line) holds a
     * complete triple — i.e. a subject is in scope, so a fresh line continues it
     * and should suggest predicates as {@code ..rel..}.
     */
    static boolean isContinuationContext(Document doc, int line) {
        for (int i = line - 1; i >= 0; i--) {
            int s = doc.getLineStartOffset(i);
            int e = doc.getLineEndOffset(i);
            String text = doc.getCharsSequence().subSequence(s, e).toString().trim();
            if (text.isEmpty()) return false;
            // The same gate the highlighter uses, so `...` is not mistaken for a
            // separator and a lone `..` does not count as a complete triple.
            if (GATE.matcher(text).find()) return true;
        }
        return false;
    }
}
