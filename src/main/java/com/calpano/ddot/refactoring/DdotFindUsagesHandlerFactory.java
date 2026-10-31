package com.calpano.ddot.refactoring;

import com.calpano.ddot.psi.DdotEntity;
import it.ddot.core.embed.DdotOccurrences;
import com.intellij.find.findUsages.FindUsagesHandler;
import com.intellij.find.findUsages.FindUsagesHandlerFactory;
import com.intellij.find.findUsages.FindUsagesOptions;
import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.usageView.UsageInfo;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Alt+F7 on an entity: the same project-wide search as rename, so Find Usages and
 * the rename preview show the same occurrences. Case variants come as non-code
 * usages, in a group of their own.
 */
public final class DdotFindUsagesHandlerFactory extends FindUsagesHandlerFactory {

    @Override
    public boolean canFindUsages(@NotNull PsiElement element) {
        return element instanceof DdotEntity;
    }

    @Override
    public @Nullable FindUsagesHandler createFindUsagesHandler(@NotNull PsiElement element, boolean forHighlightUsages) {
        return new FindUsagesHandler(element) {
            @Override
            public boolean processElementUsages(@NotNull PsiElement element, @NotNull Processor<? super UsageInfo> processor,
                                                @NotNull FindUsagesOptions options) {
                return ReadAction.compute(() -> {
                    String name = element instanceof DdotEntity e ? e.getName() : null;
                    if (name == null || name.isEmpty()) return true;
                    PsiManager psiManager = PsiManager.getInstance(element.getProject());
                    for (DdotProjectOccurrences.FileOccurrence fo : DdotProjectOccurrences.inProject(element.getProject(), name)) {
                        PsiFile file = psiManager.findFile(fo.file());
                        if (file == null) continue;
                        DdotOccurrences.Occurrence o = fo.occurrence();
                        if (!processor.process(new UsageInfo(file, o.start(), o.end(), !o.name().equals(name)))) {
                            return false;
                        }
                    }
                    return true;
                });
            }
        };
    }
}
