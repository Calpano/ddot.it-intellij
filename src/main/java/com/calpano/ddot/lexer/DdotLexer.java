package com.calpano.ddot.lexer;

import com.calpano.ddot.psi.DdotTypes;
import com.intellij.lexer.LexerBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

/**
 * Hand-written, single-state lexer for the ddot.it grammar.
 * <p>The grammar has only four kinds of separators ({@code ..}, {@code ....},
 * {@code ,,}, {@code ::}) plus whitespace, newlines and runs of "word" characters.
 * Single dots, commas and colons are part of words (URLs and the like).
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

        if (c == '.' && peekRel(1) == '.') {
            if (peekRel(2) == '.' && peekRel(3) == '.') {
                currentOffset += 4;
                tokenType = DdotTypes.DOT_DOT_DOT_DOT;
            } else {
                currentOffset += 2;
                tokenType = DdotTypes.DOT_DOT;
            }
            tokenEnd = currentOffset;
            return;
        }

        if (c == ',' && peekRel(1) == ',') {
            currentOffset += 2;
            tokenType = DdotTypes.COMMA_COMMA;
            tokenEnd = currentOffset;
            return;
        }

        if (c == ':' && peekRel(1) == ':') {
            currentOffset += 2;
            tokenType = DdotTypes.COLON_COLON;
            tokenEnd = currentOffset;
            return;
        }

        // Word: consume until next separator or whitespace.
        while (currentOffset < endOffset) {
            char cc = buffer.charAt(currentOffset);
            if (cc == '\n' || cc == ' ' || cc == '\t' || cc == '\r') break;
            if (cc == '.' && peekRel(1) == '.') break;
            if (cc == ',' && peekRel(1) == ',') break;
            if (cc == ':' && peekRel(1) == ':') break;
            currentOffset++;
        }
        tokenType = DdotTypes.WORD;
        tokenEnd = currentOffset;
    }

    private char peekRel(int offset) {
        int p = currentOffset + offset;
        if (p >= endOffset) return '\0';
        return buffer.charAt(p);
    }
}
