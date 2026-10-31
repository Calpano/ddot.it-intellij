package com.calpano.ddot.lint;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

/**
 * DL024: a name the project says is replaced (`a ..is replaced by.. has type`) is marked where it is used,
 * and Alt+Enter substitutes its replacement, as the vocabulary asks an editor to.
 */
public final class DdotReplacedNameHighlightTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("vocab.ddot", "a ..is replaced by.. has type\n");
    }

    private List<String> markedTexts() {
        String text = myFixture.getEditor().getDocument().getText();
        return myFixture.doHighlighting(HighlightSeverity.WARNING).stream()
                .filter(h -> h.getDescription() != null && h.getDescription().contains("is replaced by"))
                .map(h -> text.substring(h.getStartOffset(), h.getEndOffset()))
                .toList();
    }

    public void testUsesAreMarkedWithTheReplacementStatement() {
        myFixture.configureByText("data.ddot", "Ann ..a.. Person\nBob ..has type.. Person\n");
        assertEquals(List.of("a"), markedTexts());
        HighlightInfo info = myFixture.doHighlighting(HighlightSeverity.WARNING).stream()
                .filter(h -> h.getDescription() != null && h.getDescription().contains("is replaced by")).findFirst().orElseThrow();
        assertEquals("'a' is replaced by 'has type'. (replacement stated at vocab.ddot:1)", info.getDescription());
        assertTrue(info.getToolTip(), info.getToolTip().contains("<a href=\"#ddot-goto/"));
    }

    public void testTheDeclarationIsNotMarked() {
        myFixture.configureByText("more.ddot", "a ..is replaced by.. has type\nhas type ..replaces.. a\na ..is alias of.. has type\n");
        assertEmpty(markedTexts());
    }

    public void testAltEnterReplacesThisOccurrence() {
        myFixture.configureByText("data.ddot", "Ann ..<caret>a.. Person\nBob ..a.. Person\n");
        myFixture.doHighlighting();
        IntentionAction fix = myFixture.findSingleIntention("Replace 'a' with 'has type'");
        myFixture.launchAction(fix);
        assertEquals("Ann ..has type.. Person\nBob ..a.. Person\n", myFixture.getEditor().getDocument().getText());
    }

    public void testAltEnterReplacesAllInTheFileButNotTheDeclaration() {
        myFixture.configureByText("data.ddot",
                "a ..is replaced by.. has type\nAnn ..<caret>a.. Person\nBob ..a.. Person\n");
        myFixture.doHighlighting();
        myFixture.launchAction(myFixture.findSingleIntention("Replace all 'a' in this file with 'has type'"));
        assertEquals("a ..is replaced by.. has type\nAnn ..has type.. Person\nBob ..has type.. Person\n",
                myFixture.getEditor().getDocument().getText());
        assertEmpty(markedTexts());
    }
}
