package com.calpano.ddot.documentation;

import com.calpano.ddot.completion.DdotKgraphService;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

/**
 * The hover popup on an entity lists what the project graph states about it,
 * and each link jumps to where that triple is written.
 */
public final class DdotEntityPopupTest extends BasePlatformTestCase {

    private static final String LINK_KNOWS_BOB = DdotTripleNavigator.link("Alice", "knows", "Bob");

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("a.ddot", """
                = People
                Alice ..knows.. Bob
                Alice ..knows.. Dave
                ..likes.. Tea
                """);
        myFixture.addFileToProject("b.md", """
                # Notes

                ```ddot
                Carol ..knows.. Alice
                ```
                """);
    }

    public void testPopupListsRelationsAndInverseRelations() {
        myFixture.configureByText("c.ddot", "Bob ..knows.. <caret>Alice\n");
        String html = docAtCaret();

        assertTrue(html, html.contains("<b>Alice</b>"));
        // Outgoing: relation, then its objects.
        assertTrue(html, html.contains(a(DdotTripleNavigator.link("Alice", "knows", null), "knows") + " &rarr; "
                + a(LINK_KNOWS_BOB, "Bob") + ", " + a(DdotTripleNavigator.link("Alice", "knows", "Dave"), "Dave")));
        assertTrue(html, html.contains(a(DdotTripleNavigator.link("Alice", "likes", "Tea"), "Tea")));
        // Inverse: (x, knows, Alice) from both the Markdown fence and this file.
        assertTrue(html, html.contains("&rarr; " + a(DdotTripleNavigator.link(null, "knows", "Alice"), "knows") + ": "
                + a(DdotTripleNavigator.link("Bob", "knows", "Alice"), "Bob") + ", "
                + a(DdotTripleNavigator.link("Carol", "knows", "Alice"), "Carol")));
    }

    public void testRelationIsHoverableToo() {
        myFixture.configureByText("c.ddot", "has type ..is a.. relation\nAlice ..<caret>has type.. Person\n");
        String html = docAtCaret();
        assertTrue(html, html.contains("<b>has type</b>"));
        assertTrue(html, html.contains(a(DdotTripleNavigator.link("has type", "is a", "relation"), "relation")));
    }

    public void testSingleTripleStillShowsThePopup() {
        myFixture.configureByText("s.ddot", "<caret>Solo ..only.. One\n");
        String html = docAtCaret();
        assertTrue(html, html.contains("<b>Solo</b>"));
        assertTrue(html, html.contains(a(DdotTripleNavigator.link("Solo", "only", "One"), "One")));
    }

    public void testUnsavedEditsShowUpImmediately() {
        myFixture.configureByText("u.ddot", "Old ..is.. there\n");
        DdotKgraphService.getInstance(getProject()).facts("Old"); // build the graph before the edit
        WriteCommandAction.runWriteCommandAction(getProject(), () ->
                myFixture.getEditor().getDocument().insertString(0, "Fresh ..typed.. Now\n"));
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        myFixture.getEditor().getCaretModel().moveToOffset(1);

        String html = docAtCaret();
        assertTrue(html, html.contains(a(DdotTripleNavigator.link("Fresh", "typed", "Now"), "Now")));
        // …and the old triple has moved down one line.
        assertEquals(2, DdotKgraphService.getInstance(getProject()).locations("Old", "is", "there").getFirst().line());
    }

    public void testRelationPopupListsTriplesUsingIt() {
        myFixture.configureByText("c.ddot", "Zed ..<caret>knows.. Amy\n");
        String html = docAtCaret();
        assertTrue(html, html.contains("<b>knows</b>"));
        String link = DdotTripleNavigator.link("Alice", "knows", "Bob");
        assertTrue(html, html.contains("<li>" + a(link, "Alice") + " ..knows.. " + a(link, "Bob") + "</li>"));
        assertTrue(html, html.contains(a(DdotTripleNavigator.link("Carol", "knows", "Alice"), "Carol")));
    }

    public void testFreeTextHasNoEntityPopup() {
        myFixture.configureByText("t.ddot", "Just <caret>a note\n");
        PsiElement leaf = myFixture.getFile().findElementAt(myFixture.getCaretOffset());
        assertNull(new DdotDocumentationProvider().generateDoc(leaf, leaf));
    }

    public void testLocationsOfATriplePattern() {
        DdotKgraphService graph = DdotKgraphService.getInstance(getProject());
        List<DdotKgraphService.Location> knows = graph.locations("Alice", "knows", null);
        assertEquals(List.of(2, 3), knows.stream().map(DdotKgraphService.Location::line).toList());
        List<DdotKgraphService.Location> carol = graph.locations("Carol", "knows", "Alice");
        assertEquals(1, carol.size());
        assertTrue(carol.getFirst().url(), carol.getFirst().url().endsWith("b.md"));
        assertEquals(4, carol.getFirst().line());
    }

    public void testFollowingALinkOpensTheStatingLine() {
        myFixture.configureByText("c.ddot", "Zed ..is.. here\n");
        assertTrue(DdotTripleNavigator.follow(getProject(), DdotTripleNavigator.link("Carol", "knows", "Alice")));

        Editor editor = FileEditorManager.getInstance(getProject()).getSelectedTextEditor();
        assertNotNull(editor);
        assertEquals("b.md", editor.getVirtualFile().getName());
        assertEquals(3, editor.getCaretModel().getLogicalPosition().line); // 0-based line 3 = line 4
    }

    public void testAltEnterOffersThePopupOnEntities() {
        myFixture.configureByText("c.ddot", "Bob ..knows.. Alice<caret>\n");
        assertEquals(1, myFixture.filterAvailableIntentions("Show what the project states about 'Alice'").size());

        myFixture.configureByText("d.ddot", "Bob ..knows..<caret>  Alice\n");
        assertEmpty(myFixture.filterAvailableIntentions("Show what the project states about"));
    }

    public void testForeignLinksAreNotHandled() {
        assertFalse(DdotTripleNavigator.follow(getProject(), "https://ddot.it"));
    }

    private String docAtCaret() {
        PsiElement leaf = myFixture.getFile().findElementAt(myFixture.getCaretOffset());
        String html = new DdotDocumentationProvider().generateDoc(leaf, leaf);
        assertNotNull("no popup", html);
        return html;
    }

    private static String a(String href, String text) {
        return "<a href=\"" + href + "\">" + text + "</a>";
    }
}
