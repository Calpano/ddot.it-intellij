package com.calpano.ddot.highlighting;

import com.calpano.ddot.psi.DdotEntity;
import com.calpano.ddot.psi.DdotFile;
import com.calpano.ddot.psi.DdotLine;
import com.calpano.ddot.psi.DdotMetadata;
import com.calpano.ddot.psi.DdotOffRegions;
import com.calpano.ddot.psi.DdotPsiUtil;
import com.calpano.ddot.psi.DdotRole;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * Positional syntax highlighting — same idea as the VS Code TextMate grammar's
 * {@code entity.name.subject / .relation / .object} scopes, but applied at PSI level
 * so it can observe slot position rather than relying on regex-only rules.
 * <p>The lexer-level {@link DdotSyntaxHighlighter} sets a base color on every
 * {@code WORD} token; this annotator overrides per element with role-specific
 * attributes.
 */
public final class DdotHighlightAnnotator implements Annotator {

    /**
     * Recognized command tokens. The text matches as a single {@code WORD} (the
     * lexer keeps single dots and slashes inside words). {@code !!} also lexes as
     * a single WORD.
     */
    private static final Set<String> COMMANDS = Set.of(
            "ddot.it", "ddot.it/this", "ddot.it/on", "ddot.it/off", "!!");

    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (element instanceof DdotLine line) {
            dimIfInactive(line, holder);
            return;
        }
        if (element instanceof DdotEntity entity) {
            highlightEntity(entity, holder);
        } else if (element instanceof DdotMetadata) {
            paint(holder, element, DdotSyntaxHighlighter.METADATA);
        }
    }

    private static void dimIfInactive(DdotLine line, AnnotationHolder holder) {
        PsiFile psi = line.getContainingFile();
        if (!(psi instanceof DdotFile file)) return;
        Document doc = PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
        if (doc == null) return;
        DdotOffRegions.Result regions = DdotOffRegions.regionsFor(file);
        int lineIdx = doc.getLineNumber(line.getTextRange().getStartOffset());
        if (regions.isInactive(lineIdx)) {
            paint(holder, line, DdotSyntaxHighlighter.INACTIVE);
        }
    }

    private static void highlightEntity(DdotEntity entity, AnnotationHolder holder) {
        String name = entity.getName();
        if (name != null && COMMANDS.contains(name)) {
            paint(holder, entity, DdotSyntaxHighlighter.COMMAND);
            return;
        }
        // Free-form text on a line with no separators isn't a real triple subject
        // even though it lexes as a slot-0 entity — leave it with the lexer-level
        // base color so titles and section headers don't read as subjects.
        if (!DdotPsiUtil.isOnTripleLine(entity)) return;

        DdotRole role = entity.getRole();
        TextAttributesKey key = switch (role) {
            case SUBJECT -> DdotSyntaxHighlighter.SUBJECT;
            case PREDICATE -> DdotSyntaxHighlighter.PREDICATE;
            case OBJECT -> DdotSyntaxHighlighter.OBJECT;
            case META -> DdotSyntaxHighlighter.METADATA;
        };
        paint(holder, entity, key);
    }

    private static void paint(AnnotationHolder holder, PsiElement element, TextAttributesKey key) {
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(element)
                .textAttributes(key)
                .create();
    }
}
