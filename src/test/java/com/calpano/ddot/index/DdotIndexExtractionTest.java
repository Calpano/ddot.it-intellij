package com.calpano.ddot.index;

import it.ddot.core.DdotVocabulary;
import it.ddot.core.event.DdotEvent;
import it.ddot.core.io.DdotEventExporter;
import it.ddot.core.event.DdotTriple;
import it.ddot.core.io.DdotParser;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pure tests for the index's raw-text extraction + vocabulary classification —
 * the logic a {@code FileBasedIndex} runs per file, exercised without the
 * platform. Covers the three ddot-hosting file types.
 */
public final class DdotIndexExtractionTest {

    // --- DdotEmbeds.extract -------------------------------------------------

    @Test
    public void ddotFileIsOneChunk() {
        String src = "Alice ..knows.. Bob\n";
        assertEquals(List.of(src), DdotEmbeds.extract("ddot", src));
    }

    @Test
    public void markdownExtractsDdotFencesAndIgnoresOthers() {
        String md = String.join("\n",
                "# Title",
                "```java",
                "not ddot",
                "```",
                "```ddot",
                "Alice ..knows.. Bob",
                "```",
                "text",
                "~~~ddot.it",
                "Carol ..likes.. Tea",
                "~~~");
        List<String> chunks = DdotEmbeds.extract("md", md);
        assertEquals(List.of("Alice ..knows.. Bob", "Carol ..likes.. Tea"), chunks);
    }

    @Test
    public void asciidocExtractsListingBlocks() {
        String adoc = String.join("\n",
                "= Doc",
                "[source,ddot]",
                "----",
                "Alice ..knows.. Bob",
                "----",
                "",
                "[ddot]",
                "....",
                "Carol ..likes.. Tea",
                "....");
        List<String> chunks = DdotEmbeds.extract("adoc", adoc);
        assertEquals(List.of("Alice ..knows.. Bob", "Carol ..likes.. Tea"), chunks);
    }

    @Test
    public void unsupportedExtensionYieldsNothing() {
        assertTrue(DdotEmbeds.extract("txt", "Alice ..knows.. Bob").isEmpty());
        assertFalse(DdotEmbeds.isSupportedExtension("txt"));
        assertTrue(DdotEmbeds.isSupportedExtension("MD"));
        assertTrue(DdotEmbeds.isSupportedExtension("ddot"));
        assertTrue(DdotEmbeds.isSupportedExtension("adoc"));
    }

    // --- DdotEntityIndex.vocabulary ----------------------------------------

    @Test
    public void vocabularyClassifiesRolesAcrossChunks() {
        String md = String.join("\n",
                "```ddot",
                "Alice ..knows.. Bob",
                "Bob ..likes.. Tea",
                "```");
        Map<String, Map<String, Integer>> vocab = DdotEntityIndex.vocabulary("md", md);
        assertEquals(Set.of("Alice", "Bob"), vocab.get("S").keySet());
        assertEquals(Set.of("knows", "likes"), vocab.get("P").keySet());
        assertEquals(Set.of("Bob", "Tea"), vocab.get("O").keySet());
        // Bob is a subject once and an object once — counted per pool.
        assertEquals(Integer.valueOf(1), vocab.get("S").get("Bob"));
        assertEquals(Integer.valueOf(1), vocab.get("O").get("Bob"));
    }

    @Test
    public void vocabularyIncludesPredicateOnIncompleteTriple() {
        // No object yet — the relation is still useful for completion.
        Map<String, Map<String, Integer>> vocab = DdotEntityIndex.vocabulary("ddot", "Alice ..knows..");
        assertEquals(Set.of("knows"), vocab.get("P").keySet());
        assertEquals(Set.of("Alice"), vocab.get("S").keySet());
        assertFalse(vocab.containsKey("O"));
    }

    @Test
    public void blockCommandNotAtLineEndIsOrdinaryFieldText() {
        // block-as-field requires the opener to END its physical line. Here it
        // does not, so `ddot.it/block` is just the object's text — "a Command is
        // a part of the text of the field holding it, not a replacement for it".
        List<DdotEvent> events = DdotParser.parse(
                "john ..address.. ddot.it/block ,, ..year.. 2123\n", "ddot", "x.ddot");
        assertEquals(1, events.size());
        assertEquals("ddot.it/block", events.get(0).to);
        assertEquals(1, events.get(0).meta().size());
        assertEquals("year", events.get(0).meta().get(0).type());
        assertEquals("2123", events.get(0).meta().get(0).to());
    }

    // --- DdotTripleIndex.extract ------------------------------------------

    @Test
    public void tripleIndexExtractsFullTriples() {
        String src = String.join("\n",
                "Alice ..likes.. Tea",
                "Project Eagle .... Moonshot");   // untyped → DdotVocabulary.LINKS_TO
        List<DdotTripleIndex.Located> triples = DdotTripleIndex.extract("ddot", src, "input.ddot");
        assertEquals(List.of(
                new DdotTripleIndex.Located(new DdotTriple("Alice", "likes", "Tea"), 1),
                new DdotTripleIndex.Located(new DdotTriple("Project Eagle", DdotVocabulary.LINKS_TO, "Moonshot"), 2)),
                triples);
    }

    @Test
    public void tripleIndexLocatesTriplesInHostFile() {
        String md = String.join("\n",
                "# Title",                  // 1
                "",                         // 2
                "```ddot",                  // 3
                "Alice ..likes.. Tea",      // 4
                "..drinks.. Coffee",        // 5
                "```",                      // 6
                "text",                     // 7
                "```ddot",                  // 8
                "Bob ..knows.. Alice",      // 9
                "```");
        assertEquals(List.of(4, 5, 9), lines(DdotTripleIndex.extract("md", md, "t.md")));

        String adoc = String.join("\n",
                "= Doc",                    // 1
                "",                         // 2
                "[source,ddot]",            // 3
                "----",                     // 4
                "",                         // 5
                "Alice ..likes.. Tea",      // 6
                "----");
        assertEquals(List.of(6), lines(DdotTripleIndex.extract("adoc", adoc, "t.adoc")));
    }

    private static List<Integer> lines(List<DdotTripleIndex.Located> located) {
        return located.stream().map(DdotTripleIndex.Located::line).toList();
    }

    @Test
    public void tripleIndexSkipsDisabledSpans() {
        String src = String.join("\n",
                "ddot.it/off",
                "X ..secret.. Y",
                "ddot.it/on",
                "A ..r.. B");
        assertEquals(List.of(new DdotTripleIndex.Located(new DdotTriple("A", "r", "B"), 4)),
                DdotTripleIndex.extract("ddot", src, "input.ddot"));
    }

    @Test
    public void vocabularyExcludesCommandsAndDisabledSpans() {
        String src = String.join("\n",
                "ddot.it/off",
                "Hidden ..secret.. value",
                "ddot.it/on",
                "ddot.it/this ..is.. Active");
        Map<String, Map<String, Integer>> vocab = DdotEntityIndex.vocabulary("ddot", src);
        // Disabled-span words and the ddot.it/this command are not vocabulary.
        assertFalse(vocab.getOrDefault("S", Map.of()).containsKey("Hidden"));
        assertFalse(vocab.getOrDefault("P", Map.of()).containsKey("secret"));
        assertFalse(vocab.getOrDefault("S", Map.of()).containsKey("ddot.it/this"));
        assertEquals(Set.of("is"), vocab.get("P").keySet());
        assertEquals(Set.of("Active"), vocab.get("O").keySet());
    }
}
