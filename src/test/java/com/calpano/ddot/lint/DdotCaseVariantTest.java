package com.calpano.ddot.lint;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

/** Yellow warnings on names that differ only in letter case. */
public final class DdotCaseVariantTest extends BasePlatformTestCase {

    private List<String> warnedTexts() {
        String text = myFixture.getEditor().getDocument().getText();
        return myFixture.doHighlighting(HighlightSeverity.WARNING).stream()
                .filter(h -> h.getDescription() != null && h.getDescription().contains("differs only in letter case"))
                .map(h -> text.substring(h.getStartOffset(), h.getEndOffset()))
                .toList();
    }

    public void testBothSpellingsAcrossFilesAndRolesAreMarked() {
        VirtualFile other = myFixture.addFileToProject("other.ddot", "Project Eagle ..Leads.. Mary\n").getVirtualFile();
        myFixture.configureByText("here.ddot", String.join("\n",
                "John ..leads.. project eagle",
                "Bob ..likes.. Tea ,, ..since.. mary",
                "Same ..is.. Same", ""));
        assertEquals(List.of("leads", "project eagle", "mary"), warnedTexts());

        HighlightInfo info = myFixture.doHighlighting(HighlightSeverity.WARNING).stream()
                .filter(h -> h.getDescription() != null && h.getDescription().startsWith("'project eagle'")).findFirst().orElseThrow();
        assertEquals("'project eagle' differs only in letter case from 'Project Eagle'. "
                + "ddot.it treats them as different entities. (other spelling at other.ddot:1)", info.getDescription());
        assertTrue(info.getToolTip(), info.getToolTip().contains("<a href=\"#ddot-goto/"));

        myFixture.configureFromExistingVirtualFile(other);
        assertEquals(List.of("Project Eagle", "Leads", "Mary"), warnedTexts());
    }

    public void testConsistentSpellingIsNotMarked() {
        myFixture.addFileToProject("other.ddot", "Project Eagle ..leads.. Mary\n");
        myFixture.configureByText("here.ddot", "John ..leads.. Project Eagle\n");
        assertEmpty(warnedTexts());
    }

    public void testAliasStatementsAreNeitherMarkedNorCounted() {
        myFixture.addFileToProject("aliases.ddot", String.join("\n",
                "Foo ..has alias.. foo",
                "bar ..is alias of.. Bar",
                "Baz ..has type.. thing",
                "..has alias.. BAZ", ""));
        myFixture.configureByText("here.ddot", "Foo ..likes.. Bar\nBaz ..is.. here\n");
        assertEmpty(warnedTexts());
        myFixture.configureByText("aliases2.ddot", "Qux ..has alias.. qux\n");
        assertEmpty(warnedTexts());
    }

    public void testUseOfAnAliasedSpellingOutsideTheAliasStatementIsStillMarked() {
        myFixture.addFileToProject("aliases.ddot", "Foo ..has alias.. foo\n");
        myFixture.configureByText("here.ddot", "Foo ..likes.. tea\nfoo ..likes.. coffee\n");
        assertEquals(List.of("Foo", "foo"), warnedTexts());
    }

    public void testAltEnterUnifiesTheSpellingProjectWide() {
        VirtualFile other = myFixture.addFileToProject("other.ddot", "Project Eagle ..is.. big\n").getVirtualFile();
        myFixture.configureByText("here.ddot", "John ..leads.. project <caret>eagle\n");
        myFixture.doHighlighting();
        List<IntentionAction> fixes = myFixture.filterAvailableIntentions("Use 'Project Eagle' everywhere");
        assertEquals(1, fixes.size());
        assertEquals(1, myFixture.filterAvailableIntentions("Use 'project eagle' everywhere").size());
        myFixture.launchAction(fixes.getFirst());

        assertEquals("John ..leads.. Project Eagle\n", myFixture.getEditor().getDocument().getText());
        assertEquals("Project Eagle ..is.. big\n", FileDocumentManager.getInstance().getDocument(other).getText());
        assertEmpty(warnedTexts());
    }
}
