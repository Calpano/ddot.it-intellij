package com.calpano.ddot.parser;

import com.calpano.ddot.psi.DdotTypes;
import com.intellij.lang.ASTNode;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiParser;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

/**
 * Line-oriented parser for ddot.it.
 * <p>Each non-empty input line is wrapped in a {@code LINE} marker. Inside a
 * line, runs of {@code WORD} tokens are grouped into {@code ENTITY} markers
 * (so {@code "John Doe"} is one entity even though it lexes as two words).
 * Anything after the first {@code ,,} on a line becomes a {@code METADATA}
 * marker so downstream code can treat it as a unit.
 */
public final class DdotParser implements PsiParser {
    @Override
    public @NotNull ASTNode parse(@NotNull IElementType root, @NotNull PsiBuilder builder) {
        PsiBuilder.Marker rootMarker = builder.mark();
        while (!builder.eof()) {
            parseLine(builder);
        }
        rootMarker.done(root);
        return builder.getTreeBuilt();
    }

    private void parseLine(PsiBuilder builder) {
        IElementType first = builder.getTokenType();
        if (first == DdotTypes.NEWLINE) {
            builder.advanceLexer();
            return;
        }

        PsiBuilder.Marker lineMarker = builder.mark();
        boolean inMeta = false;
        PsiBuilder.Marker metaMarker = null;

        while (!builder.eof()) {
            IElementType type = builder.getTokenType();
            if (type == DdotTypes.NEWLINE) break;

            if (!inMeta && type == DdotTypes.COMMA_COMMA) {
                builder.advanceLexer();
                metaMarker = builder.mark();
                inMeta = true;
                continue;
            }

            if (type == DdotTypes.WORD) {
                PsiBuilder.Marker entity = builder.mark();
                builder.advanceLexer();
                while (builder.getTokenType() == DdotTypes.WORD) {
                    builder.advanceLexer();
                }
                entity.done(DdotTypes.ENTITY);
            } else {
                builder.advanceLexer();
            }
        }

        if (inMeta && metaMarker != null) {
            metaMarker.done(DdotTypes.METADATA);
        }

        if (builder.getTokenType() == DdotTypes.NEWLINE) {
            builder.advanceLexer();
        }
        lineMarker.done(DdotTypes.LINE);
    }
}
