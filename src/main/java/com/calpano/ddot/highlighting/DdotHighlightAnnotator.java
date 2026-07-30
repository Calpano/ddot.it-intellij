package com.calpano.ddot.highlighting;

import com.calpano.ddot.it.token.DdotTokenizer;
import com.calpano.ddot.psi.DdotFile;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Positional syntax highlighting driven by {@link DdotTokenizer}, the
 * corpus-pinned reference tokenizer (same token stream as the canonical
 * TextMate grammar). The annotator runs once per file: it tokenizes the file
 * text and paints each token with its role attribute. This replaces the old
 * PSI-role heuristics, so the editor inherits the tokenizer's spec-conformant
 * handling of the line gate, command shapes, {@code !!off}/{@code !!on}
 * regions, multi-line {@code ,,} meta blocks and {@code !!block} verbatim
 * bodies.
 * <p>The lexer-level {@link DdotSyntaxHighlighter} sets a base color on every
 * token; annotations layer role-specific attributes on top. Lines that fail
 * the gate (prose, titles) produce no tokens and keep the base color.
 */
public final class DdotHighlightAnnotator implements Annotator {

    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (!(element instanceof DdotFile file)) return;
        String text = file.getText();
        if (text.isEmpty()) return;

        int[] lineStarts = lineStarts(text);
        List<DdotTokenizer.Token> tokens = DdotTokenizer.tokenize(text);

        for (DdotTokenizer.Token token : tokens) {
            TextAttributesKey key = keyFor(token.token());
            if (key == null) continue;
            int start = lineStarts[token.line()] + token.start();
            int end = lineStarts[token.line()] + token.end();
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                    .range(new TextRange(start, end))
                    .textAttributes(key)
                    .create();
        }

        warnIncompleteTriples(tokens, lineStarts, holder);
    }

    /**
     * Maps the canonical token vocabulary (see {@code DdotTokenizer} /
     * {@code tokens.md}) to scheme keys. Recognition is <em>shape-based</em>,
     * exactly as the spec requires — the tokenizer emits {@code command} for any
     * of the four spellings with any name ({@code !!this}, {@code !!label},
     * {@code https://ddot.it/this}, …), and never for bare {@code ddot.it} or
     * bare {@code !!}; no name registry exists here.
     */
    private static @Nullable TextAttributesKey keyFor(@NotNull String token) {
        return switch (token) {
            case "subject" -> DdotSyntaxHighlighter.SUBJECT;
            case "relation" -> DdotSyntaxHighlighter.PREDICATE;
            case "object" -> DdotSyntaxHighlighter.OBJECT;
            case "doubledot" -> DdotSyntaxHighlighter.SEPARATOR;
            case "command" -> DdotSyntaxHighlighter.COMMAND;
            case "command-param" -> DdotSyntaxHighlighter.COMMAND_PARAM;
            case "block-end" -> DdotSyntaxHighlighter.BLOCK_END;
            case "meta-delim", "meta-separator", "meta-doubledot" -> DdotSyntaxHighlighter.METADATA_SEPARATOR;
            case "meta-relation" -> DdotSyntaxHighlighter.META_RELATION;
            case "meta-object" -> DdotSyntaxHighlighter.META_OBJECT;
            case "meta-text" -> DdotSyntaxHighlighter.META_TEXT;
            case "excluded" -> DdotSyntaxHighlighter.INACTIVE;
            case "verbatim" -> DdotSyntaxHighlighter.VERBATIM;
            default -> null;
        };
    }

    /**
     * Flag triple lines whose object slot is empty — e.g. {@code s ..p..} or
     * {@code ..p..} alone on a line. These parse to nothing in the event stream
     * (the exporter silently drops them), which is a footgun.
     * <p>A line is incomplete when it carries a main-part {@code doubledot}
     * operator but nothing fills the slot after the last one: no {@code object}
     * and no command-shaped field (a {@code !!block} opener is a filled object).
     * Meta-only continuations, verbatim bodies and excluded lines never carry a
     * main {@code doubledot} token, so they are naturally exempt.
     */
    private static void warnIncompleteTriples(List<DdotTokenizer.Token> tokens,
                                              int[] lineStarts, AnnotationHolder holder) {
        int i = 0;
        int n = tokens.size();
        while (i < n) {
            int line = tokens.get(i).line();
            int j = i;
            DdotTokenizer.Token lastDoubleDot = null;
            boolean filledAfter = false;
            while (j < n && tokens.get(j).line() == line) {
                DdotTokenizer.Token t = tokens.get(j);
                switch (t.token()) {
                    case "doubledot" -> {
                        lastDoubleDot = t;
                        filledAfter = false;
                    }
                    case "object", "command", "command-param", "block-end" -> filledAfter = true;
                    default -> {}
                }
                j++;
            }
            if (lastDoubleDot != null && !filledAfter) {
                int start = lineStarts[line] + lastDoubleDot.start();
                int end = lineStarts[line] + lastDoubleDot.end();
                holder.newAnnotation(HighlightSeverity.WARNING,
                                "Incomplete ddot.it triple: object is missing")
                        .range(new TextRange(start, end))
                        .create();
            }
            i = j;
        }
    }

    /** Offset of the first character of each {@code \n}-separated line. */
    private static int[] lineStarts(String text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') lines++;
        }
        int[] starts = new int[lines];
        int line = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') starts[line++] = i + 1;
        }
        return starts;
    }
}
