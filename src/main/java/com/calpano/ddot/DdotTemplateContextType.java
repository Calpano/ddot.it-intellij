package com.calpano.ddot;

import com.intellij.codeInsight.template.TemplateActionContext;
import com.intellij.codeInsight.template.TemplateContextType;
import org.jetbrains.annotations.NotNull;

public final class DdotTemplateContextType extends TemplateContextType {
    public DdotTemplateContextType() {
        super("ddot.it");
    }

    @Override
    public boolean isInContext(@NotNull TemplateActionContext context) {
        return context.getFile().getFileType() == DdotFileType.INSTANCE;
    }
}
