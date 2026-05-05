package com.calpano.ddot.psi;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import org.jetbrains.annotations.NotNull;

public final class DdotLine extends ASTWrapperPsiElement {
    public DdotLine(@NotNull ASTNode node) {
        super(node);
    }
}
