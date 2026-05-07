package com.calpano.ddot.psi;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Single-pass analyzer for {@code ddot.it/off} … {@code ddot.it/on} directives.
 * <p>The spec says these commands toggle whether the reader processes lines.
 * We adopt the line-level reading: a line whose trimmed text equals
 * {@code ddot.it/off} flips processing off; {@code ddot.it/on} flips it back.
 * The directive lines themselves are also inactive.
 * <p>Used by the annotator (dim inactive lines), folding builder (collapse off
 * spans), structure view (skip subjects on inactive lines) and the exporter
 * (omit triples on inactive lines).
 */
public final class DdotOffRegions {

    private static final String OFF = "ddot.it/off";
    private static final String ON = "ddot.it/on";

    /** A contiguous inclusive line range covering one off span (directives included). */
    public record Span(int startLine, int endLine, @NotNull TextRange range) {}

    public record Result(@NotNull BitSet inactive,
                         @NotNull BitSet directives,
                         @NotNull List<Span> spans) {

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
        int lc = doc.getLineCount();
        BitSet inactive = new BitSet(lc);
        BitSet directives = new BitSet(lc);
        List<Span> spans = new ArrayList<>();
        CharSequence cs = doc.getCharsSequence();

        int spanStart = -1;
        int spanStartOffset = -1;
        for (int line = 0; line < lc; line++) {
            int s = doc.getLineStartOffset(line);
            int e = doc.getLineEndOffset(line);
            String t = trimmed(cs, s, e);
            if (OFF.equals(t)) {
                directives.set(line);
                inactive.set(line);
                if (spanStart < 0) {
                    spanStart = line;
                    spanStartOffset = s;
                }
            } else if (ON.equals(t)) {
                directives.set(line);
                inactive.set(line);
                if (spanStart >= 0) {
                    spans.add(new Span(spanStart, line, new TextRange(spanStartOffset, e)));
                    spanStart = -1;
                    spanStartOffset = -1;
                }
            } else if (spanStart >= 0) {
                inactive.set(line);
            }
        }
        if (spanStart >= 0) {
            int last = lc - 1;
            int endOffset = doc.getLineEndOffset(last);
            spans.add(new Span(spanStart, last, new TextRange(spanStartOffset, endOffset)));
        }
        return new Result(inactive, directives, spans);
    }

    /**
     * Text-only fallback used when no Document is available (e.g. the JSONL
     * exporter operating on a raw string). Splits on {@code \n}; results align
     * with the Document API on normalized buffers.
     */
    public static @NotNull Result analyze(@NotNull String text) {
        BitSet inactive = new BitSet();
        BitSet directives = new BitSet();
        List<Span> spans = new ArrayList<>();

        int spanStart = -1;
        int spanStartOffset = -1;
        int line = 0;
        int i = 0;
        int len = text.length();
        while (i <= len) {
            int j = i;
            while (j < len && text.charAt(j) != '\n') j++;
            String t = text.substring(i, j).trim();
            if (OFF.equals(t)) {
                directives.set(line);
                inactive.set(line);
                if (spanStart < 0) {
                    spanStart = line;
                    spanStartOffset = i;
                }
            } else if (ON.equals(t)) {
                directives.set(line);
                inactive.set(line);
                if (spanStart >= 0) {
                    spans.add(new Span(spanStart, line, new TextRange(spanStartOffset, j)));
                    spanStart = -1;
                    spanStartOffset = -1;
                }
            } else if (spanStart >= 0) {
                inactive.set(line);
            }
            line++;
            if (j == len) break;
            i = j + 1;
        }
        if (spanStart >= 0) {
            spans.add(new Span(spanStart, line - 1, new TextRange(spanStartOffset, len)));
        }
        return new Result(inactive, directives, spans);
    }

    private static String trimmed(CharSequence cs, int start, int end) {
        int s = start;
        int e = end;
        while (s < e && Character.isWhitespace(cs.charAt(s))) s++;
        while (e > s && Character.isWhitespace(cs.charAt(e - 1))) e--;
        return cs.subSequence(s, e).toString();
    }
}
