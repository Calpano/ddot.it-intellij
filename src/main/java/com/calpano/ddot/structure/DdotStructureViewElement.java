package com.calpano.ddot.structure;

import com.calpano.ddot.psi.DdotEntity;
import com.calpano.ddot.psi.DdotFile;
import com.calpano.ddot.psi.DdotOffRegions;
import com.calpano.ddot.psi.DdotPsiUtil;
import com.calpano.ddot.psi.DdotRole;
import com.intellij.ide.projectView.PresentationData;
import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.ide.util.treeView.smartTree.TreeElement;
import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.NavigatablePsiElement;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

        DdotOffRegions.Result regions = DdotOffRegions.regionsFor(file);
        Document doc = PsiDocumentManager.getInstance(file.getProject()).getDocument(file);

        List<TreeElement> children = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (DdotEntity e : DdotPsiUtil.allEntities(file)) {
            if (e.getRole() != DdotRole.SUBJECT) continue;
            // Subject only counts when the line actually opens a triple — i.e. it has
            // at least one separator. Otherwise free-form lines like a title or section
            // header would land in the outline.
            if (!DdotPsiUtil.isOnTripleLine(e)) continue;
            if (doc != null && regions.isInactive(doc.getLineNumber(e.getTextRange().getStartOffset()))) continue;
            String name = e.getName();
            if (name == null || name.isEmpty()) continue;
            if (!seen.add(name)) continue;
            children.add(new DdotStructureViewElement(e));
        }
        return children.toArray(TreeElement.EMPTY_ARRAY);
    }

}
