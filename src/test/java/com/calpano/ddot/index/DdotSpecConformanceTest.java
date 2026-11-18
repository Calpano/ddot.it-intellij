package com.calpano.ddot.index;

import it.ddot.core.embed.DdotEmbeds;
import it.ddot.core.embed.LocatedTriple;
import it.ddot.core.event.DdotTriple;
import it.ddot.core.token.DdotTokenizer;
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Parse-Spec conformance of the plugin's <em>indexing</em> layer — {@link DdotEmbeds},
 * {@link DdotEntityIndex}, {@link DdotTripleIndex} and the completion vocabulary read from it — on edge inputs the shared golden
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

        DdotVocabulary vocab = vocabularyOf("adoc", adoc);
        Set<String> subjects = vocab.counts(Pool.SUBJECT).keySet();
        assertTrue("real subject indexed: " + subjects, subjects.contains("Alice"));
        assertFalse("metavariable NOT indexed: " + subjects, subjects.contains("aaa"));
        assertFalse(vocab.counts(Pool.RELATION).containsKey("fooRel"));
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

        Set<String> predicates = vocabularyOf("md", md).counts(Pool.RELATION).keySet();
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
        Map<String, Map<String, Integer>> commands = DdotEntityIndex.vocabulary("ddot", src);
        assertEquals(Set.of("this", "Foo"), commands.getOrDefault("C", Map.of()).keySet());
        // Commands stay OUT of the entity pools, even as a resolved `!!this`; ordinary names stay in.
        DdotVocabulary vocab = vocabularyOf("ddot", src);
        assertEquals(Set.of("active"), vocab.counts(Pool.OBJECT).keySet());
        assertEquals(Set.of("s"), vocab.counts(Pool.SUBJECT).keySet());
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

    private static List<DdotTriple> triples(List<LocatedTriple> located) {
        return located.stream().map(LocatedTriple::triple).toList();
    }

    /** What completion offers for one file's text: its triples read into a graph, as a vocabulary. */
    static DdotVocabulary vocabularyOf(String extension, String text) {
        String url = "temp:///test." + extension;
        Kgraph g = new Kgraph("test");
        DdotKgraphService.addTriples(g, url, DdotTripleIndex.extract(extension, text, "test." + extension));
        return DdotVocabulary.of(g, Set.of(url));
    }
}
