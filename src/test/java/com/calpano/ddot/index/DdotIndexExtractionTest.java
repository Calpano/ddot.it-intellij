package com.calpano.ddot.index;

import it.ddot.core.event.DdotEvent;
import it.ddot.core.io.DdotParser;
import com.calpano.ddot.completion.DdotKgraphService;
import com.calpano.ddot.completion.DdotVocabulary;
import com.calpano.ddot.completion.DdotVocabulary.Pool;
import com.calpano.kbase.inmem.Kgraph;
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
        DdotVocabulary vocab = vocabularyOf("md", md);
        assertEquals(Set.of("Alice", "Bob"), vocab.counts(Pool.SUBJECT).keySet());
        assertEquals(Set.of("knows", "likes"), vocab.counts(Pool.RELATION).keySet());
        assertEquals(Set.of("Bob", "Tea"), vocab.counts(Pool.OBJECT).keySet());
        // Bob is a subject once and an object once — counted per pool.
        assertEquals(Integer.valueOf(1), vocab.counts(Pool.SUBJECT).get("Bob"));
        assertEquals(Integer.valueOf(1), vocab.counts(Pool.OBJECT).get("Bob"));
    }

    @Test
    public void vocabularyCountsStatementsAndMeta() {
        DdotVocabulary vocab = vocabularyOf("ddot", String.join("\n",
                "Alice ..knows.. Bob ,, ..since.. 2020",
                "Alice ..knows.. Bob",
                "Carol ..knows.. Bob ,, ..since.. 2021", ""));
        // The same triple stated twice is one link with two locations: it counts twice.
        assertEquals(Integer.valueOf(3), vocab.counts(Pool.RELATION).get("knows"));
        assertEquals(Integer.valueOf(2), vocab.counts(Pool.SUBJECT).get("Alice"));
        assertEquals(Integer.valueOf(2), vocab.counts(Pool.META_RELATION).get("since"));
        assertEquals(Set.of("2020", "2021"), vocab.counts(Pool.META_OBJECT).keySet());
        // Prefix lookup ignores case and keeps the counts.
        assertEquals(java.util.Map.of("Alice", 2), vocab.withPrefix(Pool.SUBJECT, "al"));
        assertTrue(vocab.withPrefix(Pool.SUBJECT, "x").isEmpty());
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
        DdotVocabulary vocab = vocabularyOf("ddot", src);
        // Disabled-span words and the ddot.it/this command (resolved to the file) are not vocabulary.
        assertFalse(vocab.counts(Pool.SUBJECT).containsKey("Hidden"));
        assertFalse(vocab.counts(Pool.RELATION).containsKey("secret"));
        assertTrue(vocab.counts(Pool.SUBJECT).isEmpty());
        assertEquals(Set.of("is"), vocab.counts(Pool.RELATION).keySet());
        assertEquals(Set.of("Active"), vocab.counts(Pool.OBJECT).keySet());
    }

    /** What completion offers for one file's text: its triples read into a graph, as a vocabulary. */
    static DdotVocabulary vocabularyOf(String extension, String text) {
        String url = "temp:///test." + extension;
        Kgraph g = new Kgraph("test");
        DdotKgraphService.addTriples(g, url, DdotTripleIndex.extract(extension, text, "test." + extension));
        return DdotVocabulary.of(g, Set.of(url));
    }
}
