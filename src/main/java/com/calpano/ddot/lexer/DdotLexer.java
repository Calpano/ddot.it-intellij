package com.calpano.ddot.lexer;

import com.calpano.ddot.psi.DdotTypes;
import com.intellij.lexer.LexerBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

/**
 * Hand-written, single-state lexer for the ddot.it grammar.
 * <p>The grammar has four kinds of separators ({@code ..}, {@code ....},
 * {@code ,,}, {@code ;;}) plus whitespace, newlines and runs of "word"
 * characters. Per the Parse Spec's tokenization rule, symbol characters group
 * into <em>maximal runs</em> and a run is a separator only at its exact
 * special length: 2 or 4 dots, 2 commas, 2 semicolons. Every other run —
 * a single dot, an ellipsis {@code ...}, five dots, {@code ,,,} — is ordinary
 * text and stays inside a {@code WORD} (so {@code U.S.A.}, {@code Node.js}
 * and {@code Wait... what?} carry no ddot.it separators).
 */
public final class DdotLexer extends LexerBase {
    private CharSequence buffer = "";
    private int endOffset = 0;
    private int currentOffset = 0;
    private int tokenStart = 0;
    private int tokenEnd = 0;
    private IElementType tokenType = null;

    @Override
    public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int initialState) {
        this.buffer = buffer;
        this.endOffset = endOffset;
        this.currentOffset = startOffset;
        advance();
    }

    @Override
    public int getState() {
        return 0;
    }

    @Override
    public IElementType getTokenType() {
        return tokenType;
    }

    @Override
    public int getTokenStart() {
        return tokenStart;
    }

    @Override
    public int getTokenEnd() {
        return tokenEnd;
    }

    @Override
    public @NotNull CharSequence getBufferSequence() {
        return buffer;
    }

    @Override
    public int getBufferEnd() {
        return endOffset;
    }

    @Override
    public void advance() {
        tokenStart = currentOffset;
        if (currentOffset >= endOffset) {
            tokenType = null;
            tokenEnd = currentOffset;
            return;
        }

        final char c = buffer.charAt(currentOffset);

        if (c == '\n') {
            currentOffset++;
            tokenType = DdotTypes.NEWLINE;
            tokenEnd = currentOffset;
            return;
        }

        if (c == ' ' || c == '\t' || c == '\r') {
            while (currentOffset < endOffset) {
                char cc = buffer.charAt(currentOffset);
                if (cc == ' ' || cc == '\t' || cc == '\r') currentOffset++;
                else break;
            }
            tokenType = TokenType.WHITE_SPACE;
            tokenEnd = currentOffset;
            return;
        }

        // Exact-run separator semantics (Parse Spec, "Tokenization rule"):
        // a maximal run of the same symbol character is a separator only at
        // its exact special length; any other run length is text.
        if (c == '.' || c == ',' || c == ';') {
            int run = runLength(currentOffset, c);
            IElementType special = specialFor(c, run);
            if (special != null) {
                currentOffset += run;
                tokenType = special;
                tokenEnd = currentOffset;
                return;
            }
            // Not a special run — falls through into the word below.
        }

        // Word: consume until whitespace or a separator-length symbol run.
        while (currentOffset < endOffset) {
            char cc = buffer.charAt(currentOffset);
            if (cc == '\n' || cc == ' ' || cc == '\t' || cc == '\r') break;
            if (cc == '.' || cc == ',' || cc == ';') {
                int run = runLength(currentOffset, cc);
                if (specialFor(cc, run) != null) break;
                currentOffset += run;   // non-special run is word content
                continue;
            }
            currentOffset++;
        }
        tokenType = DdotTypes.WORD;
        tokenEnd = currentOffset;
    }

    /** Separator type for a maximal run of {@code run} × {@code c}, or {@code null} if it is text. */
    private static IElementType specialFor(char c, int run) {
        if (c == '.' && run == 2) return DdotTypes.DOT_DOT;
        if (c == '.' && run == 4) return DdotTypes.DOT_DOT_DOT_DOT;
        if (c == ',' && run == 2) return DdotTypes.COMMA_COMMA;
        if (c == ';' && run == 2) return DdotTypes.SEMI_SEMI;
        return null;
    }

    /** Length of the maximal run of {@code c} starting at {@code from}. */
    private int runLength(int from, char c) {
        int p = from;
        while (p < endOffset && buffer.charAt(p) == c) p++;
        return p - from;
    }
}
