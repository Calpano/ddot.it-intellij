package com.calpano.ddot.index;

import it.ddot.core.event.DdotTriple;
import it.ddot.core.token.DdotTokenizer;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Parse-Spec conformance of the plugin's <em>indexing</em> layer — {@link DdotEmbeds},
 * {@link DdotEntityIndex} and {@link DdotTripleIndex} — on edge inputs the shared golden
 * corpus does not cover: host-level {@code !!off} / {@code !!on} that spans embedded
 * chunks, and command-name harvesting.
 *
 * <p>The pure {@link DdotTokenizer} / {@code DdotEventExporter} cases that used to live
 * here moved with those classes into {@code ddot-core}
 * ({@code com.calpano.ddot.it.conformance.DdotSpecConformanceTest} in the
 * {@code ddot.it-java-core} repo). What stays here is what genuinely needs the plugin's
 * index classes.
 */
public final class DdotSpecConformanceTest {

    // --- host-level `!!off` / `!!on` around and across chunks ------------------

    @Test
    public void hostCommentOffExcludesFollowingChunks() {
        String adoc = String.join("\n",
                "// https://ddot.it/off",
                "[ddot]",
                "----",
                "aaa ..fooRel.. bbb",
                "----",
                "prose",
                "// ddot.it/on",
                "[ddot]",
                "----",
                "Alice ..knows.. Bob",
                "----", "");
        List<DdotEmbeds.Chunk> chunks = DdotEmbeds.extractChunks("adoc", adoc);
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).startsOff());
        assertFalse(chunks.get(1).startsOff());

        Map<String, Map<String, Integer>> vocab = DdotEntityIndex.vocabulary("adoc", adoc);
        Set<String> subjects = vocab.getOrDefault("S", Map.of()).keySet();
        assertTrue("real subject indexed: " + subjects, subjects.contains("Alice"));
        assertFalse("metavariable NOT indexed: " + subjects, subjects.contains("aaa"));
        assertFalse(vocab.getOrDefault("P", Map.of()).containsKey("fooRel"));
    }

    @Test
    public void offStateThreadsAcrossChunks() {
        String md = String.join("\n",
                "```ddot",
                "!!off",
                "X ..hiddenRel.. Y",
                "```",
                "prose in between",
                "```ddot",
                "A ..stillHidden.. B",
                "```",
                "<!-- !!on -->",
                "```ddot",
                "C ..visible.. D",
                "```", "");
        List<DdotEmbeds.Chunk> chunks = DdotEmbeds.extractChunks("md", md);
        assertEquals(3, chunks.size());
        assertFalse(chunks.get(0).startsOff());   // the !!off is INSIDE it
        assertTrue("off left open in chunk 1 keeps chunk 2 off", chunks.get(1).startsOff());
        assertFalse(chunks.get(2).startsOff());

        Map<String, Map<String, Integer>> vocab = DdotEntityIndex.vocabulary("md", md);
        Set<String> predicates = vocab.getOrDefault("P", Map.of()).keySet();
        assertEquals(Set.of("visible"), predicates);
        List<DdotTriple> triples = triples(DdotTripleIndex.extract("md", md, "test.md"));
        assertEquals(List.of(new DdotTriple("C", "visible", "D")), triples);
    }

    @Test
    public void onInsideAChunkReenablesItsTail() {
        String md = String.join("\n",
                "<!-- !!off -->",
                "```ddot",
                "hidden ..h.. x",
                "!!on",
                "seen ..s.. y",
                "```", "");
        List<DdotEmbeds.Chunk> chunks = DdotEmbeds.extractChunks("md", md);
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).startsOff());
        List<DdotTriple> triples = triples(DdotTripleIndex.extract("md", md, "t.md"));
        assertEquals(List.of(new DdotTriple("seen", "s", "y")), triples);
    }

    // --- command name harvesting ----------------------------------------------

    @Test
    public void commandNamesGoToTheirOwnPoolNotToVocabulary() {
        String src = "!!this ..status.. active\ns ..r.. ddot.it/Foo?q=1\n";
        Map<String, Map<String, Integer>> vocab = DdotEntityIndex.vocabulary("ddot", src);
        assertEquals(Set.of("this", "Foo"), vocab.getOrDefault("C", Map.of()).keySet());
        // Commands stay OUT of the entity pools; ordinary names stay in.
        assertTrue(vocab.getOrDefault("S", Map.of()).keySet().stream().noneMatch(n -> n.contains("this")));
        assertEquals(Set.of("active"), vocab.getOrDefault("O", Map.of()).keySet());
        assertEquals(Set.of("s"), vocab.getOrDefault("S", Map.of()).keySet());
    }

    @Test
    public void commandNameExtractionCoversAllSpellings() {
        assertEquals("this", DdotEntityIndex.commandNameOf("!!this"));
        assertEquals("this", DdotEntityIndex.commandNameOf("ddot.it/this"));
        assertEquals("this", DdotEntityIndex.commandNameOf("http://ddot.it/this"));
        assertEquals("this", DdotEntityIndex.commandNameOf("https://ddot.it/this"));
        assertEquals("foo", DdotEntityIndex.commandNameOf("ddot.it/foo?q=1#f"));
        assertEquals("foo", DdotEntityIndex.commandNameOf("!!foo#f"));
        assertNull(DdotEntityIndex.commandNameOf("plain"));
        assertNull(DdotEntityIndex.commandNameOf("ddot.it/"));
        assertNull(DdotEntityIndex.commandNameOf("!!"));
    }

    private static List<DdotTriple> triples(List<DdotTripleIndex.Located> located) {
        return located.stream().map(DdotTripleIndex.Located::triple).toList();
    }
}
