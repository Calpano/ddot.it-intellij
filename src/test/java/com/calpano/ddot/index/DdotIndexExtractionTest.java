package com.calpano.ddot.index;

import it.ddot.core.event.DdotEvent;
import it.ddot.core.io.DdotParser;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pure tests for the index's vocabulary classification — the logic a
 * {@code FileBasedIndex} runs per file, exercised without the platform. Where the
 * ddot.it of a host file is, and its triples, is the core library's and tested there
 * ({@code it.ddot.core.embed.DdotEmbedsTest}).
 */
public final class DdotIndexExtractionTest {

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
