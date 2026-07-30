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
            new AttributesDescriptor("Metadata//Metadata (base)", DdotSyntaxHighlighter.METADATA),
            new AttributesDescriptor("Metadata//Meta relation", DdotSyntaxHighlighter.META_RELATION),
            new AttributesDescriptor("Metadata//Meta object", DdotSyntaxHighlighter.META_OBJECT),
            new AttributesDescriptor("Metadata//Meta text", DdotSyntaxHighlighter.META_TEXT),
            new AttributesDescriptor("Metadata//Meta separators (,, ;; and meta ..)", DdotSyntaxHighlighter.METADATA_SEPARATOR),
            new AttributesDescriptor("Command (ddot.it/…, !!name)", DdotSyntaxHighlighter.COMMAND),
            new AttributesDescriptor("Command parameter (?end=)", DdotSyntaxHighlighter.COMMAND_PARAM),
            new AttributesDescriptor("Block end marker", DdotSyntaxHighlighter.BLOCK_END),
            new AttributesDescriptor("Verbatim block body (!!block)", DdotSyntaxHighlighter.VERBATIM),
            new AttributesDescriptor("Excluded line (between !!off and !!on)", DdotSyntaxHighlighter.INACTIVE),
            new AttributesDescriptor("Entity (base)", DdotSyntaxHighlighter.ENTITY),
            new AttributesDescriptor("Separator (.. and ....)", DdotSyntaxHighlighter.SEPARATOR),
            new AttributesDescriptor("Bad character", DdotSyntaxHighlighter.BAD_CHAR),
    };

    private static final Map<String, TextAttributesKey> TAGS = Map.ofEntries(
            Map.entry("s", DdotSyntaxHighlighter.SUBJECT),
            Map.entry("p", DdotSyntaxHighlighter.PREDICATE),
            Map.entry("o", DdotSyntaxHighlighter.OBJECT),
            Map.entry("mr", DdotSyntaxHighlighter.META_RELATION),
            Map.entry("mo", DdotSyntaxHighlighter.META_OBJECT),
            Map.entry("mt", DdotSyntaxHighlighter.META_TEXT),
            Map.entry("ms", DdotSyntaxHighlighter.METADATA_SEPARATOR),
            Map.entry("cmd", DdotSyntaxHighlighter.COMMAND),
            Map.entry("cp", DdotSyntaxHighlighter.COMMAND_PARAM),
            Map.entry("be", DdotSyntaxHighlighter.BLOCK_END),
            Map.entry("vb", DdotSyntaxHighlighter.VERBATIM),
            Map.entry("x", DdotSyntaxHighlighter.INACTIVE));

    @Override
    public @Nullable Icon getIcon() {
        return DdotIcons.FILE;
    }

    @Override
    public @NotNull SyntaxHighlighter getHighlighter() {
        return new DdotSyntaxHighlighter();
    }

    /**
     * The demo mirrors what {@link DdotHighlightAnnotator} produces in a real
     * editor (the settings preview does not run annotators, so roles are applied
     * via tags). Note the multi-line {@code ,,} block: its body lines are meta
     * content — relation/object pairs — not fresh triples.
     */
    @Override
    public @NotNull String getDemoText() {
        return """
                <s>Project Eagle</s> ..<p>started in</p>.. <o>2024</o>
                ..<p>doc site</p>.. <o>example.com/docbase/8dcjsid</o>

                <s>John Doe</s> ..<p>leads</p>.. <o>Project Eagle</o> ,, ..<mr>since</mr>.. <mo>2025</mo> ;; ..<mr>per</mr>.. <mo>CEO</mo>

                <s>Project Eagle</s> .... <o>Moonshot</o>

                <s>Dirk Hagemann</s> ..<p>works at</p>.. <o>SAP</o> ,,
                <ms>..</ms><mr>year</mr><ms>..</ms> <mo>2010</mo>
                <ms>..</ms><mr>fictive</mr><ms>..</ms> <mo>yes</mo>
                ,,

                <cmd>!!this</cmd> ..<p>topic</p>.. <o>color demo</o>

                <s>John</s> ..<p>address</p>.. <cmd>!!block</cmd><cp>?end=</cp><be>END</be>
                <vb>1 Broad Way ..not a triple.. here</vb>
                <be>END</be>

                <cmd>ddot.it/off</cmd>
                <x>Draft ..maybe.. later</x>
                <cmd>ddot.it/on</cmd>
                """;
    }

    @Override
    public @Nullable Map<String, TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap() {
        return TAGS;
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
