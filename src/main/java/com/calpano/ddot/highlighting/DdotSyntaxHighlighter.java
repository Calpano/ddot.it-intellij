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
    /**
     * {@code ,,} / {@code ;;} and the {@code ..} inside the meta part. Metadata is
     * <em>first-class content</em>, not a comment (Highlight Spec: metadata shares
     * the {@code keyword.operator.*} root with the main triple), so this falls back
     * to an operator key, not {@code LINE_COMMENT}.
     */
    public static final TextAttributesKey METADATA_SEPARATOR = TextAttributesKey.createTextAttributesKey(
            "DDOT_METADATA_SEPARATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN);
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
    /**
     * Base key for the meta part. Metadata renders as first-class content, not a
     * comment (Highlight Spec: it shares the {@code entity.name.*} root with the
     * main triple) — hence an entity-like fallback instead of {@code LINE_COMMENT}.
     */
    public static final TextAttributesKey METADATA = TextAttributesKey.createTextAttributesKey(
            "DDOT_METADATA", DefaultLanguageHighlighterColors.INSTANCE_FIELD);
    /** Meta relation ({@code ..mr..} after {@code ,,}); falls back to {@link #METADATA}. */
    public static final TextAttributesKey META_RELATION = TextAttributesKey.createTextAttributesKey(
            "DDOT_META_RELATION", METADATA);
    /** Meta object; falls back to {@link #METADATA}. */
    public static final TextAttributesKey META_OBJECT = TextAttributesKey.createTextAttributesKey(
            "DDOT_META_OBJECT", METADATA);
    /** Free-form meta text after {@code ,,}; falls back to {@link #METADATA}. */
    public static final TextAttributesKey META_TEXT = TextAttributesKey.createTextAttributesKey(
            "DDOT_META_TEXT", METADATA);
    public static final TextAttributesKey COMMAND = TextAttributesKey.createTextAttributesKey(
            "DDOT_COMMAND", DefaultLanguageHighlighterColors.STATIC_FIELD);
    /** A command's {@code ?query} / {@code #fragment} part (e.g. {@code ?end=} of {@code !!block}). */
    public static final TextAttributesKey COMMAND_PARAM = TextAttributesKey.createTextAttributesKey(
            "DDOT_COMMAND_PARAM", COMMAND);
    /** The {@code ?end=} marker value and its matching terminator line — meant to read as a pair. */
    public static final TextAttributesKey BLOCK_END = TextAttributesKey.createTextAttributesKey(
            "DDOT_BLOCK_END", DefaultLanguageHighlighterColors.CONSTANT);
    /** Body lines of a {@code !!block} — verbatim, string-literal-like. */
    public static final TextAttributesKey VERBATIM = TextAttributesKey.createTextAttributesKey(
            "DDOT_VERBATIM", DefaultLanguageHighlighterColors.STRING);

    /**
     * Foreground-only dimming applied to lines inside a {@code ddot.it/off … /on}
     * span. Falls back to the IDE's "unused element" greying so themes adopt the
     * convention automatically; layered annotations (like {@link #COMMAND} on the
     * directive token itself) still win because this key sets foreground only.
     */
    public static final TextAttributesKey INACTIVE = TextAttributesKey.createTextAttributesKey(
            "DDOT_INACTIVE", com.intellij.openapi.editor.colors.CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES);

    private static final TextAttributesKey[] SEPARATOR_KEYS = {SEPARATOR};
    private static final TextAttributesKey[] METADATA_SEPARATOR_KEYS = {METADATA_SEPARATOR};
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
        if (tokenType == DdotTypes.COMMA_COMMA || tokenType == DdotTypes.SEMI_SEMI) return METADATA_SEPARATOR_KEYS;
        if (tokenType == DdotTypes.WORD) return ENTITY_KEYS;
        if (tokenType == DdotTypes.BAD_CHAR) return BAD_CHAR_KEYS;
        return EMPTY_KEYS;
    }
}
