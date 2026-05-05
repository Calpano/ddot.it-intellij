package com.calpano.ddot.structure;

import com.intellij.ide.structureView.StructureViewModel;
import com.intellij.ide.structureView.StructureViewModelBase;
import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.ide.util.treeView.smartTree.Sorter;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

public final class DdotStructureViewModel extends StructureViewModelBase
        implements StructureViewModel.ElementInfoProvider {

    public DdotStructureViewModel(@NotNull PsiFile psiFile) {
        super(psiFile, new DdotStructureViewElement(psiFile));
        withSorters(Sorter.ALPHA_SORTER);
    }

    @Override
    public boolean isAlwaysShowsPlus(StructureViewTreeElement element) {
        return false;
    }

    @Override
    public boolean isAlwaysLeaf(StructureViewTreeElement element) {
        return !(element.getValue() instanceof PsiFile);
    }
}
