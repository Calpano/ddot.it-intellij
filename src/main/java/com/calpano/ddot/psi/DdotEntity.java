package com.calpano.ddot.psi;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import com.intellij.psi.ElementManipulators;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNamedElement;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Composite PSI for a single segment between separators (subject, predicate, or object).
 * <p>The role is positional and decided by {@link DdotPsiUtil#getRole(DdotEntity)}.
 */
public final class DdotEntity extends ASTWrapperPsiElement implements PsiNamedElement {
    public DdotEntity(@NotNull ASTNode node) {
        super(node);
    }

    @Override
    public @Nullable String getName() {
        return getText().trim();
    }

    @Override
    public PsiElement setName(@NotNull String name) throws IncorrectOperationException {
        return ElementManipulators.handleContentChange(this, name);
    }

    public @NotNull DdotRole getRole() {
        return DdotPsiUtil.getRole(this);
    }
}
