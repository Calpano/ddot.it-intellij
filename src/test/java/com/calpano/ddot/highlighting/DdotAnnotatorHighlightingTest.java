package com.calpano.ddot.highlighting;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.util.TextRange;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spec-conformance tests for the tokenizer-driven {@link DdotHighlightAnnotator}:
 * command shapes, the line gate, exact-run separators, inline {@code ;;} meta,
 * multi-line {@code ,,} meta blocks, and {@code !!block} verbatim regions.
 */
public final class DdotAnnotatorHighlightingTest extends BasePlatformTestCase {

    private String source;
    private Map<TextAttributesKey, List<TextRange>> byKey;
    private List<HighlightInfo> warnings;

    private void highlight(String... lines) {
        source = String.join("\n", lines);
        myFixture.configureByText("sample.ddot", source);
        byKey = new HashMap<>();
        warnings = new ArrayList<>();
        for (HighlightInfo info : myFixture.doHighlighting()) {
            if (info.getSeverity() == HighlightSeverity.WARNING) warnings.add(info);
            TextAttributesKey key = info.forcedTextAttributesKey;
            if (key == null) continue;
            byKey.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new TextRange(info.getStartOffset(), info.getEndOffset()));
        }
    }

    /** True iff some range with {@code key} covers the whole first occurrence of {@code text}. */
    private boolean has(TextAttributesKey key, String text) {
        int start = source.indexOf(text);
        assertTrue("marker text not found: " + text, start >= 0);
        List<TextRange> ranges = byKey.get(key);
        if (ranges == null) return false;
        for (TextRange r : ranges) {
            if (r.getStartOffset() <= start && r.getEndOffset() >= start + text.length()) return true;
        }
        return false;
    }

    private boolean anyRoleOn(String lineText) {
        int s = source.indexOf(lineText);
        assertTrue("line not found: " + lineText, s >= 0);
        int e = s + lineText.length();
        for (Map.Entry<TextAttributesKey, List<TextRange>> entry : byKey.entrySet()) {
            for (TextRange r : entry.getValue()) {
                if (r.getStartOffset() < e && r.getEndOffset() > s) return true;
            }
        }
        return false;
    }

    // --- line gate ------------------------------------------------------

    public void testEllipsisProseGetsNoColoring() {
        highlight("Wait... what?");
        assertFalse(anyRoleOn("Wait... what?"));
    }

    public void testLoneDoubleDotInProseGetsNoColoring() {
        highlight("A lone .. in prose.");
        assertFalse(anyRoleOn("A lone .. in prose."));
    }

    public void testPlainTitleLineGetsNoColoring() {
        highlight("My shopping list");
        assertFalse(anyRoleOn("My shopping list"));
    }

    public void testSpacedUntypedOperatorIsATriple() {
        highlight("Alpha .. .. Beta");
        assertTrue(has(DdotSyntaxHighlighter.SUBJECT, "Alpha"));
        assertTrue(has(DdotSyntaxHighlighter.OBJECT, "Beta"));
        // `.. ..` is ONE untyped operator, painted as one separator span.
        assertTrue(has(DdotSyntaxHighlighter.SEPARATOR, ".. .."));
    }

    public void testRegularTriple() {
        highlight("John Doe ..leads.. Project Eagle");
        assertTrue(has(DdotSyntaxHighlighter.SUBJECT, "John Doe"));
        assertTrue(has(DdotSyntaxHighlighter.PREDICATE, "leads"));
        assertTrue(has(DdotSyntaxHighlighter.OBJECT, "Project Eagle"));
    }

    // --- command shapes (no registry) ------------------------------------

    public void testBangCommandInSubjectSlot() {
        highlight("!!this ..topic.. demo");
        assertTrue(has(DdotSyntaxHighlighter.COMMAND, "!!this"));
        assertFalse(has(DdotSyntaxHighlighter.SUBJECT, "!!this"));
    }

    public void testHttpsCommandInSubjectSlot() {
        highlight("https://ddot.it/this ..topic.. demo");
        assertTrue(has(DdotSyntaxHighlighter.COMMAND, "https://ddot.it/this"));
    }

    public void testUnknownCommandNameStillHighlightsAsCommand() {
        // Shape-based: `!!label` is retired vocabulary but still command-shaped.
        highlight("a ..!!label.. b");
        assertTrue(has(DdotSyntaxHighlighter.COMMAND, "!!label"));
    }

    public void testBareDdotItIsNotACommand() {
        highlight("ddot.it ..is a.. website");
        assertFalse(has(DdotSyntaxHighlighter.COMMAND, "ddot.it"));
        assertTrue(has(DdotSyntaxHighlighter.SUBJECT, "ddot.it"));
    }

    public void testBareBangBangIsNotACommand() {
        highlight("!! ..is.. punctuation");
        assertFalse(has(DdotSyntaxHighlighter.COMMAND, "!!"));
        assertTrue(has(DdotSyntaxHighlighter.SUBJECT, "!!"));
    }

    // --- off/on ----------------------------------------------------------

    public void testBangOffOnSpellings() {
        highlight("!!off", "hidden ..from.. parsing", "!!on", "back ..to.. normal");
        assertTrue(has(DdotSyntaxHighlighter.COMMAND, "!!off"));
        assertTrue(has(DdotSyntaxHighlighter.COMMAND, "!!on"));
        assertTrue(has(DdotSyntaxHighlighter.INACTIVE, "hidden ..from.. parsing"));
        assertFalse(has(DdotSyntaxHighlighter.SUBJECT, "hidden"));
        assertTrue(has(DdotSyntaxHighlighter.SUBJECT, "back"));
    }

    public void testOffInsideHostComment() {
        highlight("<!-- ddot.it/off -->", "hidden ..a.. b", "<!-- ddot.it/on -->", "c ..d.. e");
        assertTrue(has(DdotSyntaxHighlighter.COMMAND, "ddot.it/off"));
        assertTrue(has(DdotSyntaxHighlighter.COMMAND, "ddot.it/on"));
        // The directive line itself is NOT dimmed — only the region body is.
        assertFalse(has(DdotSyntaxHighlighter.INACTIVE, "<!-- ddot.it/off -->"));
        assertTrue(has(DdotSyntaxHighlighter.INACTIVE, "hidden ..a.. b"));
        assertTrue(has(DdotSyntaxHighlighter.SUBJECT, "c"));
    }

    public void testOfficeDoesNotToggle() {
        highlight("!!office ..sells.. chairs", "still ..fully.. active");
        assertNull("nothing may be dimmed", byKey.get(DdotSyntaxHighlighter.INACTIVE));
        assertTrue(has(DdotSyntaxHighlighter.SUBJECT, "still"));
        // `!!office` is command-shaped (any name!), so it still paints as command.
        assertTrue(has(DdotSyntaxHighlighter.COMMAND, "!!office"));
    }

    // --- metadata ---------------------------------------------------------

    public void testInlineMetaWithSemicolonSeparator() {
        highlight("John ..leads.. Eagle ,, ..since.. 2025 ;; ..per.. CEO");
        assertTrue(has(DdotSyntaxHighlighter.META_RELATION, "since"));
        assertTrue(has(DdotSyntaxHighlighter.META_OBJECT, "2025"));
        assertTrue(has(DdotSyntaxHighlighter.METADATA_SEPARATOR, ";;"));
        assertTrue(has(DdotSyntaxHighlighter.META_RELATION, "per"));
        assertTrue(has(DdotSyntaxHighlighter.META_OBJECT, "CEO"));
    }

    public void testMultilineMetaBlock() {
        highlight("Dirk ..works at.. SAP ,,",
                "..year.. 2010",
                "..fictive.. yes",
                ",,",
                "Next ..line.. triple");
        // Body lines are meta content, NOT fresh triples.
        assertTrue(has(DdotSyntaxHighlighter.META_RELATION, "year"));
        assertTrue(has(DdotSyntaxHighlighter.META_OBJECT, "2010"));
        assertTrue(has(DdotSyntaxHighlighter.META_RELATION, "fictive"));
        assertTrue(has(DdotSyntaxHighlighter.META_OBJECT, "yes"));
        assertFalse(has(DdotSyntaxHighlighter.PREDICATE, "year"));
        assertFalse(has(DdotSyntaxHighlighter.OBJECT, "2010"));
        // After the closing `,,` normal triples resume.
        assertTrue(has(DdotSyntaxHighlighter.SUBJECT, "Next"));
    }

    public void testStandaloneCommaBlockOnNextLine() {
        highlight("Dirk ..works at.. SAP",
                ",,",
                "..year.. 2010",
                ",,");
        assertTrue(has(DdotSyntaxHighlighter.META_RELATION, "year"));
        assertFalse(has(DdotSyntaxHighlighter.OBJECT, "2010"));
    }

    // --- !!block verbatim regions ------------------------------------------

    public void testBlockWithEndMarker() {
        highlight("John ..address.. !!block?end=END",
                "1 Broad Way ..not a triple.. here",
                "END",
                "Dirk ..works at.. SAP");
        assertTrue(has(DdotSyntaxHighlighter.COMMAND, "!!block"));
        assertTrue(has(DdotSyntaxHighlighter.COMMAND_PARAM, "?end="));
        // Both marker occurrences read as a pair.
        int first = source.indexOf("END");
        int second = source.indexOf("\nEND") + 1;
        List<TextRange> be = byKey.get(DdotSyntaxHighlighter.BLOCK_END);
        assertNotNull(be);
        assertTrue(be.stream().anyMatch(r -> r.getStartOffset() == first));
        assertTrue(be.stream().anyMatch(r -> r.getStartOffset() == second));
        // Body is verbatim: no re-tokenization as a triple.
        assertTrue(has(DdotSyntaxHighlighter.VERBATIM, "1 Broad Way ..not a triple.. here"));
        assertFalse(has(DdotSyntaxHighlighter.SUBJECT, "1 Broad Way"));
        assertFalse(has(DdotSyntaxHighlighter.PREDICATE, "not a triple"));
        // The line after the terminator is an ordinary fresh triple.
        assertTrue(has(DdotSyntaxHighlighter.SUBJECT, "Dirk"));
    }

    public void testBlockEndedByBlankLine() {
        highlight("John ..address.. !!block",
                "verbatim ..body.. line",
                "",
                "Dirk ..works at.. SAP");
        assertTrue(has(DdotSyntaxHighlighter.COMMAND, "!!block"));
        assertTrue(has(DdotSyntaxHighlighter.VERBATIM, "verbatim ..body.. line"));
        assertFalse(has(DdotSyntaxHighlighter.SUBJECT, "verbatim"));
        assertTrue(has(DdotSyntaxHighlighter.SUBJECT, "Dirk"));
    }

    // --- incomplete-triple warning (kept feature) ---------------------------

    public void testIncompleteTripleStillWarns() {
        highlight("s ..p..");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).getDescription().contains("object is missing"));
    }

    public void testCompleteTripleDoesNotWarn() {
        highlight("s ..p.. o");
        assertEquals(0, warnings.size());
    }
}
