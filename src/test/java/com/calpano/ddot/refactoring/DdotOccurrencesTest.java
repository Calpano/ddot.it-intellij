package com.calpano.ddot.refactoring;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

/** What the rename search counts as an entity, and where. */
public final class DdotOccurrencesTest {

    private static List<String> names(String ext, String text) {
        return DdotOccurrences.inText(ext, text).stream()
                .map(o -> o.name() + "@" + text.substring(o.start(), o.end()).equals(o.name()))
                .toList();
    }

    private static List<String> plain(String ext, String text) {
        return DdotOccurrences.inText(ext, text).stream().map(DdotOccurrences.Occurrence::name).toList();
    }

    @Test
    public void allRolesIncludingMetadata() {
        String text = String.join("\n",
                "John Doe  ..leads..   Project Eagle ,, ..since.. 2025",
                "..doc site.. example.com",
                "Dirk ..works at.. SAP ,,",
                "..year.. 2010",
                ",,");
        assertEquals(List.of("John Doe", "leads", "Project Eagle", "since", "2025",
                "doc site", "example.com", "Dirk", "works at", "SAP", "year", "2010"), plain("ddot", text));
        // Ranges point exactly at the names.
        for (String n : names("ddot", text)) assertEquals(n, true, n.endsWith("@true"));
    }

    @Test
    public void proseOffRegionsBlocksAndCommandsAreNotEntities() {
        String text = String.join("\n",
                "Project Eagle notes",
                "ddot.it/off",
                "Hidden ..is.. off",
                "ddot.it/on",
                "doc ..body.. !!block?end=END",
                "Inside ..is.. verbatim",
                "END",
                "ddot.it/this ..is.. Visible");
        assertEquals(List.of("doc", "body", "is", "Visible"), plain("ddot", text));
    }

    @Test
    public void embeddedBlocksMapToHostOffsets() {
        String md = String.join("\n",
                "# Alice in prose",
                "```ddot",
                "Alice ..knows.. Bob",
                "```");
        List<DdotOccurrences.Occurrence> occ = DdotOccurrences.inText("md", md);
        assertEquals(List.of("Alice", "knows", "Bob"), occ.stream().map(DdotOccurrences.Occurrence::name).toList());
        assertEquals("Alice", md.substring(occ.getFirst().start(), occ.getFirst().end()));
        assertEquals(md.indexOf("Alice ..knows"), occ.getFirst().start());
    }

    @Test
    public void caretAtOrJustAfterAnEntity() {
        String text = "Alice ..knows.. Bob";
        assertEquals("Alice", DdotOccurrences.at("ddot", text, 5).name());
        assertEquals("knows", DdotOccurrences.at("ddot", text, 9).name());
        assertEquals(null, DdotOccurrences.at("ddot", text, 14));
    }
}
