package com.calpano.ddot.completion;

import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

/**
 * "Completion is silent in inert regions" (Autocomplete Specification): no
 * completion — automatic or explicit — inside {@code !!off}…{@code !!on}
 * excluded regions or {@code !!block} verbatim bodies. {@code myFixture.complete}
 * IS the explicit invocation, so an empty result here proves the stronger half
 * of the rule.
 */
public final class DdotInertRegionCompletionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // A corpus that would otherwise complete "Al" → Alice/Alfred everywhere.
        myFixture.addFileToProject("kb.ddot", "Alice ..likes.. Tea\nAlfred ..likes.. Coffee\n");
    }

    private void assertNoCompletion(String text, String unchanged) {
        myFixture.configureByText("cur.ddot", text);
        myFixture.complete(CompletionType.BASIC);
        List<String> shown = myFixture.getLookupElementStrings();
        assertTrue("no completion expected, got " + shown, shown == null || shown.isEmpty());
        // Nothing auto-inserted either.
        myFixture.checkResult(unchanged);
    }

    public void testNoCompletionInsideOffRegion() {
        assertNoCompletion(
                "!!off\nAl<caret>\n!!on\n",
                "!!off\nAl\n!!on\n");
    }

    public void testNoCompletionOnBlankLineInsideOffRegion() {
        assertNoCompletion(
                "!!off\n<caret>\n!!on\n",
                "!!off\n\n!!on\n");
    }

    public void testNoCompletionInsideBlockBody() {
        assertNoCompletion(
                "!!block\nAl<caret>\n",
                "!!block\nAl\n");
    }

    public void testNoCompletionInsideMarkedBlockBodyAfterBlankLine() {
        // The ?end= form survives blank lines — still verbatim, still silent.
        assertNoCompletion(
                "!!block?end=EOF\n\nAl<caret>\nEOF\n",
                "!!block?end=EOF\n\nAl\nEOF\n");
    }

    public void testCompletionResumesAfterOnCommand() {
        myFixture.configureByText("cur.ddot", "!!off\nhidden\n!!on\nAl<caret>");
        myFixture.complete(CompletionType.BASIC);
        List<String> shown = myFixture.getLookupElementStrings();
        assertNotNull("completion works again after !!on", shown);
        assertTrue(shown.toString(), shown.contains("Alice") && shown.contains("Alfred"));
    }

    public void testCompletionResumesAfterUnmarkedBlockEnds() {
        // A blank line ends the unmarked block form.
        myFixture.configureByText("cur.ddot", "!!block\nverbatim body\n\nAl<caret>");
        myFixture.complete(CompletionType.BASIC);
        List<String> shown = myFixture.getLookupElementStrings();
        assertNotNull("completion works again after the block body", shown);
        assertTrue(shown.toString(), shown.contains("Alice") && shown.contains("Alfred"));
    }

    public void testMetaTextStaysSilent() {
        // Meta text is the third silent context; the `..` snippet leak is gone too.
        assertNoCompletion(
                "a ..b.. c ,, free note.<caret>\n",
                "a ..b.. c ,, free note.\n");
    }
}
