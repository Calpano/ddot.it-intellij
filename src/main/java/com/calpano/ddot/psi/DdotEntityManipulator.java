package com.calpano.ddot.psi;

import com.calpano.ddot.DdotFileType;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.AbstractElementManipulator;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Lets the rename refactoring (and any other framework code that goes through
 * {@code ElementManipulators}) replace an entity's textual content. The new text
 * is parsed inside a synthetic ddot.it document so multi-word entities like
 * {@code "John Doe"} survive the lex/parse round trip and reproduce as a single
 * {@link DdotEntity} composite.
 */
public final class DdotEntityManipulator extends AbstractElementManipulator<DdotEntity> {

    @Override
    public @Nullable DdotEntity handleContentChange(@NotNull DdotEntity element,
                                                    @NotNull TextRange range,
                                                    String newContent) throws IncorrectOperationException {
        if (newContent == null) newContent = "";
        // Reject names that would re-introduce separators — these would split into
        // multiple entities and corrupt the surrounding triple shape.
        if (newContent.contains("..") || newContent.contains(",,") || newContent.contains("::")) {
            throw new IncorrectOperationException(
                    "ddot.it entity names cannot contain '..', ',,' or '::' separators");
        }

        String oldText = element.getText();
        String replaced = oldText.substring(0, range.getStartOffset())
                + newContent
                + oldText.substring(range.getEndOffset());

        // Build a synthetic single-line file whose first segment is the new entity text.
        // Trailing scaffolding (`..r.. o`) gives the lexer a complete typed-link line so
        // the new text parses as a SUBJECT-position ENTITY composite.
        PsiFile dummy = PsiFileFactory.getInstance(element.getProject())
                .createFileFromText("__rename__.ddot", DdotFileType.INSTANCE,
                        replaced + " ..r.. o");
        DdotEntity replacement = PsiTreeUtil.findChildOfType(dummy, DdotEntity.class);
        if (replacement == null) return element;

        return (DdotEntity) element.replace(replacement);
    }
}
