package com.calpano.ddot.index;

import com.calpano.ddot.export.DdotEvent;
import com.calpano.ddot.export.DdotEventExporter;
import com.calpano.ddot.highlighting.DdotTokenizer;
import com.calpano.ddot.highlighting.DdotTokenizer.Token;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pure (no-platform) checks for the Parse-Spec conformance fixes in
 * {@link DdotTokenizer}, {@link DdotEventExporter} and {@link DdotEmbeds}. The
 * shared golden corpus does not cover these edge inputs, so they are pinned
 * here instead.
 */
public final class DdotSpecConformanceTest {

    private static List<Token> tok(String src) {
        return DdotTokenizer.tokenize(src);
    }

    private static List<Token> ofType(List<Token> toks, String type) {
        return toks.stream().filter(t -> t.token().equals(type)).collect(Collectors.toList());
    }

    private static List<DdotEvent> events(String src) {
        return DdotEventExporter.parse(src, "ddot", "test");
    }

    // --- EM2 guards: a 3+ run of `!` is plain text --------------------------

    @Test
    public void tripleBangOffIsNotADirective() {
        List<Token> toks = tok("A ..p.. B\n!!!off\nC ..q.. D");
        assertTrue("no command token for !!!off: " + toks, ofType(toks, "command").isEmpty());
        // The line after `!!!off` is NOT excluded — it parses normally.
        assertTrue(toks.stream().anyMatch(
                t -> t.line() == 2 && t.token().equals("subject") && t.text().equals("C")));
        assertTrue(ofType(toks, "excluded").isEmpty());
    }

    @Test
    public void tripleBangOnDoesNotCloseAnOffRegion() {
        List<Token> toks = tok("!!off\nhidden ..x.. y\n!!!on\nstill ..x.. y\n!!on\nback ..p.. o");
        // `!!!on` stays excluded content; only the real `!!on` closes.
        assertTrue(toks.stream().anyMatch(
                t -> t.line() == 2 && t.token().equals("excluded") && t.text().equals("!!!on")));
        assertTrue(toks.stream().anyMatch(t -> t.line() == 3 && t.token().equals("excluded")));
        assertTrue(toks.stream().anyMatch(
                t -> t.line() == 5 && t.token().equals("subject") && t.text().equals("back")));
    }

    @Test
    public void tripleBangCommandInSlotStaysPlainSlotText() {
        List<Token> toks = tok("A ..p.. !!!this");
        assertTrue(ofType(toks, "command").isEmpty());
        assertEquals("!!!this", ofType(toks, "object").get(0).text());
    }

    @Test
    public void offOnHelpersMatchAllFourSpellingsExactly() {
        for (String s : List.of("!!off", "ddot.it/off", "http://ddot.it/off", "https://ddot.it/off",
                "ddot.it/off?x=1", "  !!off  ")) {
            assertTrue(s, DdotTokenizer.isOffCommand(s));
        }
        for (String s : List.of("!!!off", "!!office", "ddot.it", "ddot.it/", "!!on", "off", "x !!off")) {
            assertFalse(s, DdotTokenizer.isOffCommand(s));
        }
        assertTrue(DdotTokenizer.isOnCommand("!!on"));
        assertTrue(DdotTokenizer.isOnCommand("https://ddot.it/on"));
        assertFalse(DdotTokenizer.isOnCommand("!!!on"));
        assertFalse(DdotTokenizer.isOnCommand("!!online"));
    }

    // --- nested in-text commands (Highlight Spec D6) ------------------------

    @Test
    public void commandInsideSlotTextEmitsNestedCommandToken() {
        List<Token> toks = tok("a ..spec.. see ddot.it/this for details");
        Token obj = ofType(toks, "object").get(0);
        assertEquals("see ddot.it/this for details", obj.text());
        Token cmd = ofType(toks, "command").get(0);
        assertEquals("ddot.it/this", cmd.text());
        // The nested token covers exactly the command span inside the slot.
        assertEquals(obj.start() + "see ".length(), cmd.start());
        assertEquals(cmd.start() + "ddot.it/this".length(), cmd.end());
    }

    @Test
    public void nestedCommandRecognisedInAllFourSpellings() {
        for (String spelling : List.of("!!x", "ddot.it/x", "http://ddot.it/x", "https://ddot.it/x")) {
            List<Token> toks = tok("a ..p.. before " + spelling + " after");
            assertEquals(spelling, List.of(spelling),
                    ofType(toks, "command").stream().map(Token::text).collect(Collectors.toList()));
        }
        // A bare `ddot.it` (no slash+name) is NOT a command, even in text.
        assertTrue(ofType(tok("a ..p.. see ddot.it for info"), "command").isEmpty());
    }

    // --- command-param for every command ------------------------------------

    @Test
    public void commandQueryAndFragmentAreACommandParamToken() {
        List<Token> toks = tok("a ..p.. ddot.it/foo?q=1#frag");
        Token cmd = ofType(toks, "command").get(0);
        Token param = ofType(toks, "command-param").get(0);
        assertEquals("ddot.it/foo", cmd.text());
        assertEquals("?q=1#frag", param.text());
        assertEquals(cmd.end(), param.start());
    }

    @Test
    public void nestedCommandSplitsItsParamToo() {
        List<Token> toks = tok("a ..p.. see !!foo#frag here");
        assertEquals("!!foo", ofType(toks, "command").get(0).text());
        assertEquals("#frag", ofType(toks, "command-param").get(0).text());
    }

    // --- MetaNextInline: after `;;` only `..`/`....` may follow --------------

    @Test
    public void textAfterInlineMetaSeparatorMakesTheLineNotATriple() {
        assertTrue(tok("s ..p.. o ,, ..a.. b ;; junk").isEmpty());
        assertTrue(tok("s ..p.. o ,, ..a.. b ;; junk ..c.. d").isEmpty());
        // Trailing `;;` with nothing after it: MetaNextInline accepts no NL.
        assertTrue(tok("s ..p.. o ,, ..a.. b ;;").isEmpty());
        // Control: a well-formed second pair keeps the line.
        List<Token> ok = tok("s ..p.. o ,, ..a.. b ;; ..c.. d");
        assertEquals(List.of("a", "c"), ofType(ok, "meta-relation").stream()
                .map(Token::text).collect(Collectors.toList()));
    }

    // --- blank-line rules for `,,` blocks ------------------------------------

    @Test
    public void blankLineBreaksStandaloneCommaAttachment() {
        List<Token> toks = tok("s ..p.. o\n\n,,\nnote\n,,");
        assertTrue("unattached `,,` yields no tokens: " + toks, ofType(toks, "meta-delim").isEmpty());
        assertTrue(ofType(toks, "meta-text").isEmpty());
        // Control: immediately after the triple it attaches.
        List<Token> ok = tok("s ..p.. o\n,,\nnote\n,,");
        assertEquals(2, ofType(ok, "meta-delim").size());
        assertEquals("note", ofType(ok, "meta-text").get(0).text());
    }

    @Test
    public void commaAfterNoTripleIsNotATriple() {
        assertTrue(tok(",,\nnote\n,,").isEmpty());
    }

    @Test
    public void blankLineInsideMetaBlockAbortsIt() {
        List<Token> toks = tok("s ..p.. o\n,,\nnote\n\n..x.. y");
        // After the aborting blank line, `..x.. y` is a plain (subject-less)
        // triple line again, not meta content.
        assertTrue(toks.stream().anyMatch(
                t -> t.line() == 4 && t.token().equals("relation") && t.text().equals("x")));
        assertTrue(toks.stream().noneMatch(
                t -> t.line() == 4 && t.token().startsWith("meta-")));
    }

    // --- `!!block` opener positions ------------------------------------------

    @Test
    public void blockOpenerInvalidInMetaRelationPosition() {
        List<Token> toks = tok("s ..p.. o ,, ..!!block\nnext ..is.. here");
        // Not an opener: no verbatim region; it is command-shaped slot text.
        assertTrue(ofType(toks, "verbatim").isEmpty());
        assertTrue(toks.stream().anyMatch(
                t -> t.line() == 0 && t.token().equals("command") && t.text().equals("!!block")));
        assertTrue(toks.stream().anyMatch(
                t -> t.line() == 1 && t.token().equals("subject") && t.text().equals("next")));
    }

    @Test
    public void blockOpenerStillValidInValuePositions() {
        // Object position (existing behavior, must survive the phase guard).
        List<Token> toks = tok("s ..p.. !!block\nbody line\n\nnext ..is.. here");
        assertEquals("body line", ofType(toks, "verbatim").get(0).text());
    }

    // --- exporter: subject register resets at `!!off` -------------------------

    @Test
    public void subjectRegisterDoesNotSurviveAnOffRegion() {
        List<DdotEvent> evs = events("A ..p.. B\n!!off\nX\n!!on\n..q.. C\n");
        assertEquals(1, evs.size());
        assertEquals("A", evs.get(0).from);
        // Control: without the off region the shorthand line binds to A.
        List<DdotEvent> control = events("A ..p.. B\n..q.. C\n");
        assertEquals(2, control.size());
        assertEquals("A", control.get(1).from);
    }

    // --- exporter: unterminated / aborted `,,` blocks -------------------------

    @Test
    public void unterminatedMetaBlockEmitsNoTriple() {
        assertTrue(events("A ..p.. B ,,\n..k.. v\n").isEmpty());
        assertTrue(events("A ..p.. B\n,,\nnote\n").isEmpty());
        // Control: the closed block emits the triple with its meta.
        List<DdotEvent> ok = events("A ..p.. B ,,\n..k.. v\n,,\n");
        assertEquals(1, ok.size());
        assertEquals(1, ok.get(0).meta.size());
        assertEquals("k", ok.get(0).meta.get(0).type());
        assertEquals("v", ok.get(0).meta.get(0).to());
    }

    @Test
    public void blankLineAbortsMetaBlockAndDropsItsTriple() {
        List<DdotEvent> evs = events("A ..p.. B ,,\nnote\n\nC ..q.. D\n");
        assertEquals(1, evs.size());
        assertEquals("C", evs.get(0).from);
    }

    @Test
    public void blankBlockTerminatorInsideMetaDoesNotAbortIt() {
        // 30-block-meta-object shape: the blank line TERMINATES the unmarked
        // `!!block`, it is not a free blank line inside a `,,` block.
        List<DdotEvent> evs = events("Dirk ..works at.. SAP ,, ..note.. !!block\nline one\n\nnext ..is.. here\n");
        assertEquals(2, evs.size());
        assertEquals("line one", evs.get(0).meta.get(0).to());
    }

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
        List<DdotTriple> triples = DdotTripleIndex.extract("md", md, "test.md");
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
        List<DdotTriple> triples = DdotTripleIndex.extract("md", md, "t.md");
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
}
