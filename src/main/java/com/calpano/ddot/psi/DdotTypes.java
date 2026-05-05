package com.calpano.ddot.psi;

import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;

/**
 * All token types and composite element types used by the ddot.it parser.
 * <p>Token types are produced by the lexer; element types are emitted by the parser
 * as composite PSI nodes.
 */
public interface DdotTypes {
    // Tokens
    IElementType WORD = new DdotTokenType("WORD");
    IElementType DOT_DOT = new DdotTokenType("DOT_DOT");
    IElementType DOT_DOT_DOT_DOT = new DdotTokenType("DOT_DOT_DOT_DOT");
    IElementType COMMA_COMMA = new DdotTokenType("COMMA_COMMA");
    IElementType COLON_COLON = new DdotTokenType("COLON_COLON");
    IElementType NEWLINE = new DdotTokenType("NEWLINE");
    IElementType BAD_CHAR = new DdotTokenType("BAD_CHAR");

    // Composite elements
    IElementType LINE = new DdotElementType("LINE");
    IElementType ENTITY = new DdotElementType("ENTITY");
    IElementType METADATA = new DdotElementType("METADATA");

    TokenSet SEPARATORS = TokenSet.create(DOT_DOT, DOT_DOT_DOT_DOT);
    TokenSet COMMENTS = TokenSet.EMPTY;
    TokenSet STRINGS = TokenSet.EMPTY;
}
