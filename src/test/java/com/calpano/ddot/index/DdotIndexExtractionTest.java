package com.calpano.ddot.index;

import com.calpano.ddot.export.DdotEvent;
import com.calpano.ddot.export.DdotEventExporter;
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
        List<DdotEvent> events = DdotEventExporter.parse(
                "john ..address.. ddot.it/block ,, ..year.. 2123\n", "ddot", "x.ddot");
        assertEquals(1, events.size());
        assertEquals("ddot.it/block", events.get(0).to);
        assertEquals(1, events.get(0).meta.size());
        assertEquals("year", events.get(0).meta.get(0).type());
        assertEquals("2123", events.get(0).meta.get(0).to());
    }

    // --- DdotTripleIndex.extract ------------------------------------------

    @Test
    public void tripleIndexExtractsFullTriples() {
        String src = String.join("\n",
                "Alice ..likes.. Tea",
                "Project Eagle .... Moonshot");   // untyped → null predicate
        List<DdotTriple> triples = DdotTripleIndex.extract("ddot", src, "input.ddot");
        assertEquals(List.of(
                new DdotTriple("Alice", "likes", "Tea"),
                new DdotTriple("Project Eagle", null, "Moonshot")), triples);
    }

    @Test
    public void tripleIndexSkipsDisabledSpans() {
        String src = String.join("\n",
                "ddot.it/off",
                "X ..secret.. Y",
                "ddot.it/on",
                "A ..r.. B");
        assertEquals(List.of(new DdotTriple("A", "r", "B")),
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
