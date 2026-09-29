package com.calpano.ddot.structure;

import com.calpano.ddot.psi.DdotEntity;
import com.calpano.ddot.psi.DdotFile;
import com.intellij.ide.projectView.PresentationData;
import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.ide.util.treeView.smartTree.TreeElement;
import com.intellij.navigation.ItemPresentation;
import com.intellij.psi.NavigatablePsiElement;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

public final class DdotStructureViewElement implements StructureViewTreeElement {
    private final PsiElement element;

    public DdotStructureViewElement(@NotNull PsiElement element) {
        this.element = element;
    }

    @Override
    public Object getValue() {
        return element;
    }

    @Override
    public void navigate(boolean requestFocus) {
        if (element instanceof NavigatablePsiElement nav) nav.navigate(requestFocus);
    }

    @Override
    public boolean canNavigate() {
        return element instanceof NavigatablePsiElement nav && nav.canNavigate();
    }

    @Override
    public boolean canNavigateToSource() {
        return element instanceof NavigatablePsiElement nav && nav.canNavigateToSource();
    }

    @Override
    public @NotNull ItemPresentation getPresentation() {
        if (element instanceof DdotFile file) {
            return new PresentationData(file.getName(), null, file.getIcon(0), null);
        }
        if (element instanceof DdotEntity entity) {
            return new PresentationData(entity.getName(), null, null, null);
        }
        return new PresentationData(element.toString(), null, null, null);
    }

    @Override
    public TreeElement @NotNull [] getChildren() {
        if (!(element instanceof DdotFile file)) return EMPTY_ARRAY;
        return DdotOutline.build(file).toArray(TreeElement.EMPTY_ARRAY);
    }
}
