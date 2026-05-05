package com.calpano.ddot;

import com.intellij.openapi.fileTypes.LanguageFileType;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public final class DdotFileType extends LanguageFileType {
    public static final DdotFileType INSTANCE = new DdotFileType();

    private DdotFileType() {
        super(DdotLanguage.INSTANCE);
    }

    @Override
    public @NonNls @NotNull String getName() {
        return "ddot.it";
    }

    @Override
    public @NotNull String getDescription() {
        return "ddot.it knowledge graph";
    }

    @Override
    public @NotNull String getDefaultExtension() {
        return "ddot";
    }

    @Override
    public Icon getIcon() {
        return DdotIcons.FILE;
    }
}
