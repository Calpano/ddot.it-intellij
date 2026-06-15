package com.calpano.ddot;

import com.intellij.icons.AllIcons;
import com.intellij.json.JsonLanguage;
import com.intellij.openapi.fileTypes.LanguageFileType;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * Claims the compound <code>*.cj.json</code> name pattern and routes those files
 * through the bundled JSON language, so they get full JSON parsing, highlighting
 * and completion. Registered with {@code patterns="*.cj.json"} in plugin.xml —
 * a glob matcher rather than a plain extension, since the platform's extension
 * matching only sees the last dotted segment ({@code json}).
 *
 * <p>Delegating to {@link JsonLanguage#INSTANCE} (instead of registering a brand
 * new language) is why this needs {@code <depends>com.intellij.modules.json</depends>}.
 */
public final class CjJsonFileType extends LanguageFileType {
    public static final CjJsonFileType INSTANCE = new CjJsonFileType();

    private CjJsonFileType() {
        super(JsonLanguage.INSTANCE);
    }

    @Override
    public @NonNls @NotNull String getName() {
        return "CJ JSON";
    }

    @Override
    public @NotNull String getDescription() {
        return "Calpano JSON (*.cj.json)";
    }

    @Override
    public @NotNull String getDefaultExtension() {
        return "cj.json";
    }

    @Override
    public Icon getIcon() {
        return AllIcons.FileTypes.Json;
    }
}
