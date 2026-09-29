package com.calpano.ddot.structure;

import com.calpano.ddot.psi.DdotLine;
import com.intellij.ide.projectView.PresentationData;
import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.ide.util.treeView.smartTree.TreeElement;
import com.intellij.navigation.ItemPresentation;
import com.intellij.ui.SimpleTextAttributes;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * An AsciiDoc section heading ({@code = Title}, {@code == Title}, …) in the
 * outline. Children are nested sections and the subjects inside the section.
 */
public final class DdotSectionElement implements StructureViewTreeElement {
    private final DdotLine line;
    private final String title;
    private final int level;
    final List<TreeElement> children = new ArrayList<>();

    DdotSectionElement(@NotNull DdotLine line, @NotNull String title, int level) {
        this.line = line;
        this.title = title;
        this.level = level;
    }

    public @NotNull String getTitle() {
        return title;
    }

    int getLevel() {
        return level;
    }

    @Override
    public Object getValue() {
        return line;
    }

    @Override
    public void navigate(boolean requestFocus) {
        line.navigate(requestFocus);
    }

    @Override
    public boolean canNavigate() {
        return line.canNavigate();
    }

    @Override
    public boolean canNavigateToSource() {
        return line.canNavigateToSource();
    }

    @Override
    public @NotNull ItemPresentation getPresentation() {
        PresentationData presentation = new PresentationData(title, null, null, null);
        presentation.addText(title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
        return presentation;
    }

    @Override
    public TreeElement @NotNull [] getChildren() {
        return children.toArray(TreeElement.EMPTY_ARRAY);
    }
}
