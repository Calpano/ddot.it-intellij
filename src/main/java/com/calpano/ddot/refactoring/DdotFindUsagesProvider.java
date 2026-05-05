package com.calpano.ddot.refactoring;

import com.calpano.ddot.lexer.DdotLexer;
import com.calpano.ddot.psi.DdotEntity;
import com.calpano.ddot.psi.DdotTypes;
import com.intellij.lang.cacheBuilder.DefaultWordsScanner;
import com.intellij.lang.cacheBuilder.WordsScanner;
import com.intellij.lang.findUsages.FindUsagesProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.TokenSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Tells IntelliJ that ddot.it entities are searchable so Find Usages
 * (Alt+F7) can show every place an entity name appears.
 * <p>The words scanner indexes individual {@code WORD} tokens, which is the
 * right granularity even for multi-word entity names — IntelliJ's index uses
 * any contained word as a search hint, then the rename processor (or default
 * reference scan) refines the matches.
 */
public final class DdotFindUsagesProvider implements FindUsagesProvider {

    @Override
    public @Nullable WordsScanner getWordsScanner() {
        return new DefaultWordsScanner(
                new DdotLexer(),
                TokenSet.create(DdotTypes.WORD),
                TokenSet.EMPTY,
                TokenSet.EMPTY);
    }

    @Override
    public boolean canFindUsagesFor(@NotNull PsiElement element) {
        return element instanceof DdotEntity;
    }

    @Override
    public @Nullable String getHelpId(@NotNull PsiElement element) {
        return null;
    }

    @Override
    public @NotNull String getType(@NotNull PsiElement element) {
        return "entity";
    }

    @Override
    public @NotNull String getDescriptiveName(@NotNull PsiElement element) {
        if (element instanceof DdotEntity entity) {
            String name = entity.getName();
            return name != null ? name : "";
        }
        return "";
    }

    @Override
    public @NotNull String getNodeText(@NotNull PsiElement element, boolean useFullName) {
        return getDescriptiveName(element);
    }
}
