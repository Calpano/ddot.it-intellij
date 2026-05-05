package com.calpano.ddot.psi;

import com.calpano.ddot.DdotFileType;
import com.calpano.ddot.DdotLanguage;
import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.FileViewProvider;
import org.jetbrains.annotations.NotNull;

public final class DdotFile extends PsiFileBase {
    public DdotFile(@NotNull FileViewProvider viewProvider) {
        super(viewProvider, DdotLanguage.INSTANCE);
    }

    @Override
    public @NotNull FileType getFileType() {
        return DdotFileType.INSTANCE;
    }

    @Override
    public String toString() {
        return "ddot.it File";
    }
}
