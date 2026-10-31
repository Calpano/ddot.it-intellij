package com.calpano.ddot.lint;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

/**
 * DL023 and the linter's report-only pitfalls in the editor: findings of the core library's analysis, shown
 * where they are. (The test platform has no Markdown plugin, so nothing is injected into a {@code .md} file
 * here; Markdown files still count through the index.)
 */
public final class DdotRelationSidesHighlightTest extends BasePlatformTestCase {

    private List<HighlightInfo> warnings(String containing) {
        return myFixture.doHighlighting(HighlightSeverity.WARNING).stream()
                .filter(h -> h.getDescription() != null && h.getDescription().contains(containing))
                .toList();
    }

    private String text(HighlightInfo h) {
        return myFixture.getEditor().getDocument().getText().substring(h.getStartOffset(), h.getEndOffset());
    }

    public void testRelationUnderBothSidesIsWarnedOnEveryStatementPlacingIt() {
        // The other statement is in a ddot block of a Markdown file: it counts through the index, and is named
        // by its line in the Markdown file.
        myFixture.addFileToProject("schema.md", "# Schema\n```ddot\nlinks to ..has subtype.. depends on\n```\n");
        myFixture.configureByText("here.ddot", "is linked from ..has subtype.. depends on\nx ..depends on.. y\n");
        List<HighlightInfo> ws = warnings("lies under both");
        assertEquals(1, ws.size());
        assertEquals("is linked from ..has subtype.. depends on", text(ws.getFirst()));
        assertTrue(ws.getFirst().getDescription(), ws.getFirst().getDescription().endsWith("(also placed at schema.md:3)"));
    }

    public void testAltEnterJumpsToTheOtherStatement() {
        myFixture.addFileToProject("schema.ddot", "links to ..has subtype.. depends on\n");
        myFixture.configureByText("here.ddot", "is linked from ..has <caret>subtype.. depends on\n");
        myFixture.doHighlighting();
        List<IntentionAction> fixes = myFixture.filterAvailableIntentions("Go to the other statement placing the relation (schema.ddot:1)");
        assertEquals(1, fixes.size());
    }

    public void testRelationsWithADirectionAreNotWarned() {
        myFixture.configureByText("here.ddot", "links to ..has subtype.. depends on\ndepends on ..has inverse.. required by\n");
        assertEmpty(warnings("lies under both"));
    }

    public void testLinterPitfallsAreShown() {
        myFixture.configureByText("here.ddot", "S ... O\n");
        List<HighlightInfo> ws = myFixture.doHighlighting(HighlightSeverity.WARNING);
        assertEquals(1, ws.size());
        assertEquals("S ... O", text(ws.getFirst()));
    }
}
