package com.calpano.ddot.psi;

import com.calpano.ddot.it.token.DdotTokenizer;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Set;

/**
 * Analyzer for {@code !!off} … {@code !!on} directives (Parse Spec, "Pre-Parse
 * Commands").
 * <p>Recognition is delegated to {@link DdotTokenizer}, the corpus-pinned
 * reference tokenizer, so all four command spellings ({@code !!off},
 * {@code ddot.it/off}, {@code http://ddot.it/off}, {@code https://ddot.it/off};
 * same for {@code on}) are found <em>anywhere</em> on a line — the common case
 * is inside a host-language comment such as {@code <!-- ddot.it/off -->}. The
 * command name is matched exactly ({@code !!office} is not {@code !!off}), and
 * commands inside a {@code !!block} body are inert.
 * <p>The directive line itself is <em>not</em> part of the excluded region: it
 * is flagged in {@link Result#directives()} only, while {@link Result#inactive()}
 * covers just the lines strictly between {@code off} and {@code on}. The
 * {@link Span}s handed to the folding builder still include the directive lines
 * so the whole construct folds as one unit.
 * <p>Used by the annotator (dim excluded lines), folding builder (collapse off
 * spans) and structure view (skip subjects on excluded lines).
 */
public final class DdotOffRegions {

    private static final Set<String> OFF_COMMANDS = Set.of(
            "!!off", "ddot.it/off", "http://ddot.it/off", "https://ddot.it/off");
    private static final Set<String> ON_COMMANDS = Set.of(
            "!!on", "ddot.it/on", "http://ddot.it/on", "https://ddot.it/on");

    /** A contiguous inclusive line range covering one off span (directives included). */
    public record Span(int startLine, int endLine, @NotNull TextRange range) {}

    public record Result(@NotNull BitSet inactive,
                         @NotNull BitSet directives,
                         @NotNull List<Span> spans) {

        /** True for lines strictly between an off and its on directive — not the directives themselves. */
        public boolean isInactive(int line) {
            return inactive.get(line);
        }

        public boolean isDirective(int line) {
            return directives.get(line);
        }
    }

    private DdotOffRegions() {}

    /**
     * Cached per-PsiFile. Recomputed when the document or PSI changes.
     */
    public static @NotNull Result regionsFor(@NotNull DdotFile file) {
        return CachedValuesManager.getCachedValue(file, () -> {
            Document doc = PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
            Result r = doc != null ? analyze(doc) : analyze(file.getText());
            return CachedValueProvider.Result.create(r, file);
        });
    }

    public static @NotNull Result analyze(@NotNull Document doc) {
        // IntelliJ documents always use \n, so the text analysis lines up.
        return analyze(doc.getCharsSequence().toString());
    }

    public static @NotNull Result analyze(@NotNull String text) {
        String[] lines = text.split("\n", -1);
        int lineCount = lines.length;
        int[] lineStart = new int[lineCount];
        for (int i = 1; i < lineCount; i++) {
            lineStart[i] = lineStart[i - 1] + lines[i - 1].length() + 1;
        }

        // Which lines carry an off/on command token? The tokenizer already
        // implements the recognition rules (all four spellings, anywhere on the
        // line, exact name match, inert inside !!block bodies): a toggling
        // command surfaces as a `command` token whose text is exactly the
        // command spelling, group-matched without any ?query/#fragment suffix.
        BitSet offCmd = new BitSet(lineCount);
        BitSet onCmd = new BitSet(lineCount);
        for (DdotTokenizer.Token t : DdotTokenizer.tokenize(text)) {
            if (!"command".equals(t.token())) continue;
            // Exact-run rule for the `!!` prefix (EM2): in `!!!off` the bang run
            // has length 3, so it is text, not a command. The tokenizer's regex
            // lacks this lookbehind, so guard here on the raw line.
            if (t.text().startsWith("!!") && t.start() > 0
                    && lines[t.line()].charAt(t.start() - 1) == '!') continue;
            if (OFF_COMMANDS.contains(t.text())) offCmd.set(t.line());
            else if (ON_COMMANDS.contains(t.text())) onCmd.set(t.line());
        }

        BitSet inactive = new BitSet(lineCount);
        BitSet directives = new BitSet(lineCount);
        List<Span> spans = new ArrayList<>();

        boolean off = false;
        int spanStart = -1;
        for (int line = 0; line < lineCount; line++) {
            if (!off && offCmd.get(line)) {
                directives.set(line);
                off = true;
                spanStart = line;
            } else if (off && onCmd.get(line)) {
                directives.set(line);
                off = false;
                spans.add(new Span(spanStart, line, new TextRange(
                        lineStart[spanStart], lineStart[line] + lines[line].length())));
                spanStart = -1;
            } else if (off) {
                inactive.set(line);
            }
        }
        if (off) {
            int last = lineCount - 1;
            spans.add(new Span(spanStart, last, new TextRange(
                    lineStart[spanStart], lineStart[last] + lines[last].length())));
        }
        return new Result(inactive, directives, spans);
    }
}
