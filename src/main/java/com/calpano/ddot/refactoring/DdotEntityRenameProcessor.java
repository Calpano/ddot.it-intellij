package com.calpano.ddot.refactoring;

import com.calpano.ddot.psi.DdotEntity;
import com.calpano.ddot.psi.DdotFile;
import com.calpano.ddot.psi.DdotPsiUtil;
import com.intellij.psi.PsiElement;
import com.intellij.refactoring.rename.RenamePsiElementProcessor;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * In ddot.it there is no formal declaration / reference distinction — every
 * occurrence of an entity name is a peer. So when the user renames one occurrence,
 * extend the rename batch to every other entity in the same file with the same
 * (trimmed) name. IntelliJ then drives the actual text replacement through
 * {@link com.calpano.ddot.psi.DdotEntityManipulator} on each.
 */
public final class DdotEntityRenameProcessor extends RenamePsiElementProcessor {

    @Override
    public boolean canProcessElement(@NotNull PsiElement element) {
        if (!(element instanceof DdotEntity entity)) return false;
        String name = entity.getName();
        return name != null && !name.isEmpty();
    }

    @Override
    public void prepareRenaming(@NotNull PsiElement element,
                                @NotNull String newName,
                                @NotNull Map<PsiElement, String> allRenames) {
        if (!(element instanceof DdotEntity entity)) return;
        String oldName = entity.getName();
        if (oldName == null || oldName.isEmpty()) return;
        if (!(entity.getContainingFile() instanceof DdotFile file)) return;

        for (DdotEntity other : DdotPsiUtil.allEntities(file)) {
            if (other == entity) continue;
            if (oldName.equals(other.getName())) {
                allRenames.put(other, newName);
            }
        }
    }
}
