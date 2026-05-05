package com.calpano.ddot.highlighting;

import com.calpano.ddot.lexer.DdotLexer;
import com.calpano.ddot.psi.DdotTypes;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

public final class DdotSyntaxHighlighter extends SyntaxHighlighterBase {
    public static final TextAttributesKey SEPARATOR = TextAttributesKey.createTextAttributesKey(
            "DDOT_SEPARATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN);
    public static final TextAttributesKey METADATA_SEPARATOR = TextAttributesKey.createTextAttributesKey(
            "DDOT_METADATA_SEPARATOR", DefaultLanguageHighlighterColors.LINE_COMMENT);
    public static final TextAttributesKey COLON_SEPARATOR = TextAttributesKey.createTextAttributesKey(
            "DDOT_COLON_SEPARATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN);
    public static final TextAttributesKey ENTITY = TextAttributesKey.createTextAttributesKey(
            "DDOT_ENTITY", DefaultLanguageHighlighterColors.IDENTIFIER);
    public static final TextAttributesKey BAD_CHAR = TextAttributesKey.createTextAttributesKey(
            "DDOT_BAD_CHARACTER", com.intellij.openapi.editor.HighlighterColors.BAD_CHARACTER);

    /**
     * Positional/role colors — applied by {@code DdotHighlightAnnotator} on top of
     * the lexer-level {@link #ENTITY} so subjects, predicates, objects, metadata,
     * and {@code ddot.it/...} commands each get their own scheme entry.
     * <p>Fallbacks are picked from keys that are reliably <em>colored</em> in both
     * the Light and Darcula schemes — {@code CLASS_NAME} and {@code INSTANCE_METHOD}
     * have no foreground in Light, so subjects/predicates would have looked like
     * plain text there.
     */
    public static final TextAttributesKey SUBJECT = TextAttributesKey.createTextAttributesKey(
            "DDOT_SUBJECT", DefaultLanguageHighlighterColors.STRING);
    public static final TextAttributesKey PREDICATE = TextAttributesKey.createTextAttributesKey(
            "DDOT_PREDICATE", DefaultLanguageHighlighterColors.NUMBER);
    public static final TextAttributesKey OBJECT = TextAttributesKey.createTextAttributesKey(
            "DDOT_OBJECT", DefaultLanguageHighlighterColors.KEYWORD);
    public static final TextAttributesKey METADATA = TextAttributesKey.createTextAttributesKey(
            "DDOT_METADATA", DefaultLanguageHighlighterColors.LINE_COMMENT);
    public static final TextAttributesKey COMMAND = TextAttributesKey.createTextAttributesKey(
            "DDOT_COMMAND", DefaultLanguageHighlighterColors.STATIC_FIELD);

    private static final TextAttributesKey[] SEPARATOR_KEYS = {SEPARATOR};
    private static final TextAttributesKey[] METADATA_SEPARATOR_KEYS = {METADATA_SEPARATOR};
    private static final TextAttributesKey[] COLON_SEPARATOR_KEYS = {COLON_SEPARATOR};
    private static final TextAttributesKey[] ENTITY_KEYS = {ENTITY};
    private static final TextAttributesKey[] BAD_CHAR_KEYS = {BAD_CHAR};
    private static final TextAttributesKey[] EMPTY_KEYS = new TextAttributesKey[0];

    @Override
    public @NotNull Lexer getHighlightingLexer() {
        return new DdotLexer();
    }

    @Override
    public TextAttributesKey @NotNull [] getTokenHighlights(IElementType tokenType) {
        if (tokenType == DdotTypes.DOT_DOT || tokenType == DdotTypes.DOT_DOT_DOT_DOT) return SEPARATOR_KEYS;
        if (tokenType == DdotTypes.COMMA_COMMA) return METADATA_SEPARATOR_KEYS;
        if (tokenType == DdotTypes.COLON_COLON) return COLON_SEPARATOR_KEYS;
        if (tokenType == DdotTypes.WORD) return ENTITY_KEYS;
        if (tokenType == DdotTypes.BAD_CHAR) return BAD_CHAR_KEYS;
        return EMPTY_KEYS;
    }
}
