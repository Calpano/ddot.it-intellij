package com.calpano.ddot.refactoring;

import com.calpano.ddot.psi.DdotEntity;
import com.intellij.openapi.util.Condition;
import com.intellij.psi.PsiElement;

/**
 * Keeps IntelliJ's generic element rename away from ddot.it entities, so
 * {@link DdotRenameHandler} is the only rename offered for them.
 */
public final class DdotRenameVeto implements Condition<PsiElement> {
    @Override
    public boolean value(PsiElement element) {
        return element instanceof DdotEntity;
    }
}
