package com.calpano.ddot.folding;

import com.intellij.lang.ASTNode;
import com.intellij.lang.folding.FoldingBuilderEx;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.FoldingGroup;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Folds runs of triple/continuation lines, breaking on blank lines.
 * <p>The original VS Code provider opens a region whenever it sees a line
 * containing {@code ..} and closes it at the next blank line — this mirrors
 * that behavior on top of the document line numbering.
 */
public final class DdotFoldingBuilder extends FoldingBuilderEx implements DumbAware {

    @Override
    public FoldingDescriptor @NotNull [] buildFoldRegions(@NotNull PsiElement root, @NotNull Document document, boolean quick) {
        List<FoldingDescriptor> result = new ArrayList<>();

        int lineCount = document.getLineCount();
        Integer foldStartLine = null;

        for (int i = 0; i < lineCount; i++) {
            int s = document.getLineStartOffset(i);
            int e = document.getLineEndOffset(i);
            String line = document.getCharsSequence().subSequence(s, e).toString().trim();

            if (line.contains("..")) {
                if (foldStartLine == null) foldStartLine = i;
            }

            if (line.isEmpty() && foldStartLine != null && i > foldStartLine) {
                addFold(result, document, foldStartLine, i - 1, root.getNode());
                foldStartLine = null;
            }
        }
        if (foldStartLine != null && lineCount > foldStartLine + 1) {
            addFold(result, document, foldStartLine, lineCount - 1, root.getNode());
        }

        return result.toArray(FoldingDescriptor.EMPTY_ARRAY);
    }

    private void addFold(List<FoldingDescriptor> result, Document document, int startLine, int endLine,
                         ASTNode root) {
        if (endLine <= startLine) return;
        int startOffset = document.getLineEndOffset(startLine);
        int endOffset = document.getLineEndOffset(endLine);
        if (endOffset > startOffset) {
            // Each region gets its own group so toggling one doesn't toggle siblings.
            FoldingGroup group = FoldingGroup.newGroup("ddot." + startLine);
            result.add(new FoldingDescriptor(root, new TextRange(startOffset, endOffset), group));
        }
    }

    @Override
    public String getPlaceholderText(@NotNull ASTNode node) {
        return "...";
    }

    @Override
    public boolean isCollapsedByDefault(@NotNull ASTNode node) {
        return false;
    }

    @SuppressWarnings("unused")
    private static Document docFor(PsiElement root) {
        return PsiDocumentManager.getInstance(root.getProject()).getDocument(root.getContainingFile());
    }
}
