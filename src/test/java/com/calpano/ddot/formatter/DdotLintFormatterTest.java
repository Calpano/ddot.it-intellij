package com.calpano.ddot.formatter;

import it.ddot.core.io.DdotEventExporter;
import it.ddot.core.io.DdotParser;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Regression protection for {@link DdotLintFormatter} — the guarantees {@code linting.adoc} demands of a conforming
 * formatter, and which the deleted hand-rolled {@code DdotFormatter} had no tests for (and in fact violated).
 *
 * <p>The corpus-driven checks run over the shared golden corpus at
 * {@code ../ddot.it/test-data/cases/}, located via the {@code ddot.test-data.dir} system property (set by surefire in
 * {@code pom.xml}) — same convention as {@code PreviewHtmlGoldenTest}. When the corpus is absent the corpus checks warn
 * and skip; the hand-written unit checks below always run.
 */
public final class DdotLintFormatterTest {

    private static final String DIR_PROP = "ddot.test-data.dir";

    // ---- corpus-wide invariants -------------------------------------------------------

    /** The corpus case directories that have an {@code input.ddot}; empty when the corpus is absent. */
    private static List<Path> corpusCases() throws IOException {
        String configured = System.getProperty(DIR_PROP);
        if (configured == null || configured.isBlank()) {
            System.err.println("[DdotLintFormatterTest] `" + DIR_PROP + "` not set; skipping corpus checks.");
            return List.of();
        }
        Path casesDir = Path.of(configured, "cases");
        if (!Files.isDirectory(casesDir)) {
            System.err.println("[DdotLintFormatterTest] no corpus at " + casesDir + "; skipping corpus checks.");
            return List.of();
        }
        try (Stream<Path> s = Files.list(casesDir)) {
            return s.filter(Files::isDirectory)
                    .filter(p -> Files.isRegularFile(p.resolve("input.ddot")))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
    }

    private static String rstrip(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }

    /**
     * {@code linting.adoc} §"Constructs the formatter must leave alone": the interior of a {@code !!block} is a raw
     * multi-line value and must survive byte-for-byte. Every corpus input is used as a block body — the most hostile
     * bodies available, full of {@code ..} operators, {@code ,,} metadata, commands and odd whitespace.
     */
    @Test
    public void blockBodiesPassThroughUntouchedOverCorpus() throws IOException {
        List<String> failures = new ArrayList<>();
        for (Path caseDir : corpusCases()) {
            String body = Files.readString(caseDir.resolve("input.ddot"));
            if (body.endsWith("\n")) body = body.substring(0, body.length() - 1);
            String doc = "doc ..content.. !!block?end=ENDOFBLOCK\n" + body + "\nENDOFBLOCK\n";
            String formatted = DdotLintFormatter.formatDocument(doc);
            String expected = "doc ..content.. !!block?end=ENDOFBLOCK\n" + body + "\nENDOFBLOCK\n";
            if (!formatted.equals(expected)) {
                failures.add(caseDir.getFileName().toString() + "\n  got: " + formatted);
            }
        }
        if (!failures.isEmpty()) fail("block body was reformatted in: " + String.join("\n", failures));
    }

    @Test
    public void blockBodyWithTripleSyntaxIsVerbatim() {
        String doc = String.join("\n",
                "john   ..address..   !!block?end=END",
                "  a ..b..   c   ",
                "\tnot   ..a triple",
                "x .... y ,,   note",
                "END",
                "john   ..age..   11", "");
        assertEquals(String.join("\n",
                "john ..address.. !!block?end=END",
                "  a ..b..   c   ",
                "\tnot   ..a triple",
                "x .... y ,,   note",
                "END",
                "john ..age.. 11", ""), DdotLintFormatter.formatDocument(doc));
    }

    // ---- block / verbatim safety (self-contained) ---------------------------------------

    @Test
    public void defaultBlockEndsAtBlankLineAndBodyIsVerbatim() {
        String doc = String.join("\n",
                "john ..address.. ddot.it/block",
                "  Broadway 1   ..not.. a triple",
                "",
                "john   ..age.. 11", "");
        assertEquals(String.join("\n",
                "john ..address.. ddot.it/block",
                "  Broadway 1   ..not.. a triple",
                "",
                "john ..age.. 11", ""), DdotLintFormatter.formatDocument(doc));
    }

    /** {@code linting.adoc} §"Guarantees" 2: {@code format(format(x)) == format(x)}. */
    @Test
    public void idempotentOverCorpus() throws IOException {
        List<String> failures = new ArrayList<>();
        for (Path caseDir : corpusCases()) {
            String input = Files.readString(caseDir.resolve("input.ddot"));
            String once = DdotLintFormatter.formatDocument(input);
            String twice = DdotLintFormatter.formatDocument(once);
            if (!once.equals(twice)) failures.add(caseDir.getFileName().toString());
        }
        assertEquals("formatting is not a fixed point for: " + failures, List.of(), failures);
    }

    /** {@code linting.adoc} §"Guarantees" 1: the multiset of extracted triples is unchanged. */
    @Test
    public void meaningPreservingOverCorpus() throws IOException {
        List<String> failures = new ArrayList<>();
        for (Path caseDir : corpusCases()) {
            String name = caseDir.getFileName().toString();
            String input = Files.readString(caseDir.resolve("input.ddot"));
            String before = DdotEventExporter.toJsonl(DdotParser.parse(input, "ddot", "input.ddot"));
            String after = DdotEventExporter.toJsonl(
                    DdotParser.parse(DdotLintFormatter.formatDocument(input), "ddot", "input.ddot"));
            if (!before.equals(after)) failures.add(name + "\n  before: " + before + "\n  after:  " + after);
        }
        if (!failures.isEmpty()) fail("formatting changed the graph in: " + String.join("\n", failures));
    }

    // ---- the bugs the old DdotFormatter shipped -----------------------------------------

    /** A multi-line-metadata opener stays a line <em>ending</em> in {@code ␣,,} (DL009). */
    @Test
    public void multiLineMetaOpenerHasNoTrailingSpace() {
        String doc = String.join("\n",
                "Dirk Hagemann   ..works at..   SAP ,,",
                "..role.. Architect",
                ",,", "");
        String out = DdotLintFormatter.formatDocument(doc);
        assertTrue("expected no trailing space, got [" + out.split("\n")[0] + "]",
                out.startsWith("Dirk Hagemann ..works at.. SAP ,,\n"));
    }

    /**
     * {@code linting.adoc} §"Implementation mapping", meta caveat: a triple carrying more than one meta pair falls back
     * to edge-trim-only rather than being respelled (or dropped). The old formatter rewrote it to
     * {@code ,, ..since..2025 ;;..until..2027}.
     */
    @Test
    public void multiPairMetadataIsPreserved() {
        String line = "John Doe ..leads.. Project Eagle ,, ..since.. 2025 ;; ..until.. 2027";
        assertEquals(line, DdotLintFormatter.formatDocument(line));
    }

    /** The whole corpus is already canonical after one pass — no leftover trailing whitespace. */
    @Test
    public void noTrailingWhitespaceIntroducedOverCorpus() throws IOException {
        List<String> failures = new ArrayList<>();
        for (Path caseDir : corpusCases()) {
            String input = Files.readString(caseDir.resolve("input.ddot"));
            String[] in = input.split("\n", -1);
            String[] out = DdotLintFormatter.formatDocument(input).split("\n", -1);
            for (int i = 0; i < out.length; i++) {
                boolean wasClean = i >= in.length || in[i].equals(rstrip(in[i]));
                if (wasClean && !out[i].equals(rstrip(out[i]))) {
                    failures.add(caseDir.getFileName().toString() + " line " + (i + 1) + ": [" + out[i] + "]");
                }
            }
        }
        assertEquals("trailing whitespace introduced: " + failures, List.of(), failures);
    }

    /**
     * {@code linting.adoc} §"Object exception": everything after the second {@code ..} is the object verbatim. The old
     * formatter split on <em>every</em> {@code ..}, so it collapsed these lines to {@code a..spec..http://h/x..y}.
     */
    @Test
    public void objectContainingDotDotIsVerbatim() {
        assertEquals("a ..spec.. http://h/x..y",
                DdotLintFormatter.formatDocument("a ..spec.. http://h/x..y"));
        assertEquals("a ..links to.. ../../b.adoc",
                DdotLintFormatter.formatDocument("a  ..links to..   ../../b.adoc"));
    }

    /**
     * {@code linting.adoc} principle 3, prose-safety. The old formatter turned this sentence into
     * {@code A lone..in prose, only one marker.}
     */
    @Test
    public void proseIsByteForByte() {
        String prose = String.join("\n",
                "A lone .. in prose, only one marker.",
                "Three dots ... are not an operator.",
                "  indented prose   with spacing  ", "");
        assertEquals(prose, DdotLintFormatter.formatDocument(prose));
    }

    // ---- corpus plumbing ---------------------------------------------------------------

    @Test
    public void trailingNewlineIsPreserved() {
        assertEquals("a ..p.. b\n", DdotLintFormatter.formatDocument("a   ..p..   b\n"));
        assertEquals("a ..p.. b", DdotLintFormatter.formatDocument("a   ..p..   b"));
        assertEquals("", DdotLintFormatter.formatDocument(""));
    }

    @Test
    public void verbatimAndOffRegionsAreUntouched() {
        String doc = String.join("\n",
                "a   ..p..   !!verbatim   b ,, c",
                "!!off",
                "sloppy   ..line..   here",
                "!!on",
                "tidy   ..line..   here", "");
        assertEquals(String.join("\n",
                "a   ..p..   !!verbatim   b ,, c",
                "!!off",
                "sloppy   ..line..   here",
                "!!on",
                "tidy ..line.. here", ""), DdotLintFormatter.formatDocument(doc));
    }


    // ---- Reformat: full triples + canonical spacing -------------------------------------

    @Test
    public void reformatFillsInheritedSubjectAndBalancesWhitespace() {
        String doc = String.join("\n",
                "Project Eagle   ..started in..2024",
                "..doc site..   example.com/docbase/8dcjsid",
                "   ..owner..Max  ,, ..since.. 2025",
                "",
                "John Doe  ..leads..   Project Eagle", "");
        assertEquals(String.join("\n",
                "Project Eagle ..started in.. 2024",
                "Project Eagle ..doc site.. example.com/docbase/8dcjsid",
                "Project Eagle ..owner.. Max ,, ..since.. 2025",
                "",
                "John Doe ..leads.. Project Eagle", ""), DdotReformat.format(doc));
    }

    @Test
    public void reformatLeavesMetadataBlocksOffRegionsAndProseAlone() {
        String doc = String.join("\n",
                "Metadata",
                "Dirk Hagemann ..works at.. SAP ,,",
                "..year.. 2010",
                ",,",
                "..born in.. Bonn",
                "ddot.it/off",
                "Draft ..maybe.. later",
                "..maybe not.. who knows",
                "ddot.it/on", "");
        assertEquals(String.join("\n",
                "Metadata",
                "Dirk Hagemann ..works at.. SAP ,,",
                "..year.. 2010",
                ",,",
                "Dirk Hagemann ..born in.. Bonn",
                "ddot.it/off",
                "Draft ..maybe.. later",
                "..maybe not.. who knows",
                "ddot.it/on", ""), DdotReformat.format(doc));
    }

    @Test
    public void reformatIsIdempotentAndMeaningPreservingOverCorpus() throws IOException {
        List<String> failures = new ArrayList<>();
        for (Path caseDir : corpusCases()) {
            String name = caseDir.getFileName().toString();
            String input = Files.readString(caseDir.resolve("input.ddot"));
            String once = DdotReformat.format(input);
            if (!once.equals(DdotReformat.format(once))) failures.add(name + ": not idempotent");
            String before = DdotEventExporter.toJsonl(DdotParser.parse(input, "ddot", "input.ddot"));
            String after = DdotEventExporter.toJsonl(DdotParser.parse(once, "ddot", "input.ddot"));
            if (!before.equals(after)) failures.add(name + "\n  before: " + before + "\n  after:  " + after);
        }
        if (!failures.isEmpty()) fail("reformat broke: " + String.join("\n", failures));
    }
}
