package com.calpano.ddot.highlighting;

import com.calpano.ddot.DdotIcons;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.options.colors.AttributesDescriptor;
import com.intellij.openapi.options.colors.ColorDescriptor;
import com.intellij.openapi.options.colors.ColorSettingsPage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Map;

public final class DdotColorSettingsPage implements ColorSettingsPage {
    private static final AttributesDescriptor[] DESCRIPTORS = new AttributesDescriptor[]{
            new AttributesDescriptor("Subject", DdotSyntaxHighlighter.SUBJECT),
            new AttributesDescriptor("Predicate", DdotSyntaxHighlighter.PREDICATE),
            new AttributesDescriptor("Object", DdotSyntaxHighlighter.OBJECT),
            new AttributesDescriptor("Metadata", DdotSyntaxHighlighter.METADATA),
            new AttributesDescriptor("Command (ddot.it/..., !!)", DdotSyntaxHighlighter.COMMAND),
            new AttributesDescriptor("Entity (base)", DdotSyntaxHighlighter.ENTITY),
            new AttributesDescriptor("Separator (.. and ....)", DdotSyntaxHighlighter.SEPARATOR),
            new AttributesDescriptor("Metadata separator (,,)", DdotSyntaxHighlighter.METADATA_SEPARATOR),
            new AttributesDescriptor("Colon separator (::)", DdotSyntaxHighlighter.COLON_SEPARATOR),
            new AttributesDescriptor("Bad character", DdotSyntaxHighlighter.BAD_CHAR),
    };

    @Override
    public @Nullable Icon getIcon() {
        return DdotIcons.FILE;
    }

    @Override
    public @NotNull SyntaxHighlighter getHighlighter() {
        return new DdotSyntaxHighlighter();
    }

    @Override
    public @NotNull String getDemoText() {
        return """
                Project Eagle ..started in.. 2024
                ..doc site.. example.com/docbase/8dcjsid

                John Doe ..leads.. Project Eagle ,, ..since.. 2025

                Project Eagle .... Moonshot

                Dirk Hagemann ..works at.. SAP ,,
                ..year.. 2010
                ..fictive.. yes
                ,,
                """;
    }

    @Override
    public @Nullable Map<String, TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap() {
        return null;
    }

    @Override
    public AttributesDescriptor @NotNull [] getAttributeDescriptors() {
        return DESCRIPTORS;
    }

    @Override
    public ColorDescriptor @NotNull [] getColorDescriptors() {
        return ColorDescriptor.EMPTY_ARRAY;
    }

    @Override
    public @NotNull String getDisplayName() {
        return "ddot.it";
    }
}
