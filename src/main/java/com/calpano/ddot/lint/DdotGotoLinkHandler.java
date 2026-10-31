package com.calpano.ddot.lint;

import it.ddot.core.lint.Location;
import com.intellij.codeInsight.highlighting.TooltipLinkHandler;
import com.intellij.openapi.editor.Editor;
import org.jetbrains.annotations.NotNull;

/** Follows {@code #ddot-goto/…} links in warning tooltips. */
public final class DdotGotoLinkHandler extends TooltipLinkHandler {
    @Override
    public boolean handleLink(@NotNull String refSuffix, @NotNull Editor editor) {
        Location loc = DdotGoto.parse(refSuffix);
        return loc != null && editor.getProject() != null && DdotGoto.open(editor.getProject(), loc);
    }
}
