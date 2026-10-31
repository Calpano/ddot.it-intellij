package com.calpano.ddot.lint;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import it.ddot.core.lint.Location;

import java.util.List;

/** Cardinality violations as yellow warnings that lead to where the bound is stated. */
public final class DdotCardinalityHighlightTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("schema.ddot", "= Schema\nemail ..max cardinality.. 1\n");
    }

    private List<HighlightInfo> warnings() {
        return myFixture.doHighlighting(HighlightSeverity.WARNING).stream()
                .filter(h -> h.getDescription() != null && h.getDescription().contains("cardinality stated at"))
                .toList();
    }

    public void testViolatingLinesAreWarnedWithTheStatementLocation() {
        myFixture.configureByText("people.ddot", "John ..email.. a@x\n  ..email.. b@x\nMary ..email.. c@x\n");
        List<HighlightInfo> ws = warnings();
        assertEquals(2, ws.size());
        String text = myFixture.getEditor().getDocument().getText();
        assertEquals("John ..email.. a@x", text.substring(ws.get(0).getStartOffset(), ws.get(0).getEndOffset()));
        assertEquals("..email.. b@x", text.substring(ws.get(1).getStartOffset(), ws.get(1).getEndOffset()));
        assertEquals(HighlightSeverity.WARNING, ws.getFirst().getSeverity());
        assertEquals("'John' has 2 values for 'email', but at most 1 is allowed. (cardinality stated at schema.ddot:2)",
                ws.getFirst().getDescription());
        assertTrue(ws.getFirst().getToolTip(), ws.getFirst().getToolTip().contains("<a href=\"#ddot-goto/"));
    }

    public void testAltEnterJumpsToTheStatement() {
        myFixture.configureByText("people.ddot", "John ..em<caret>ail.. a@x\n..email.. b@x\n");
        myFixture.doHighlighting();
        List<IntentionAction> fixes = myFixture.filterAvailableIntentions("Go to cardinality statement (schema.ddot:2)");
        assertEquals(1, fixes.size());
        myFixture.launchAction(fixes.getFirst());
        Editor editor = FileEditorManager.getInstance(getProject()).getSelectedTextEditor();
        assertEquals("schema.ddot", editor.getVirtualFile().getName());
        assertEquals(1, editor.getCaretModel().getLogicalPosition().line);
    }

    public void testFixingTheDataClearsTheWarning() {
        myFixture.configureByText("people.ddot", "John ..email.. a@x\n..email.. b@x\n");
        assertEquals(2, warnings().size());
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            var doc = myFixture.getEditor().getDocument();
            doc.deleteString(doc.getLineStartOffset(1), doc.getTextLength());
        });
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        assertEmpty(warnings());
    }

    public void testTooltipLinkRoundTrip() {
        Location loc = new Location("temp:///src/a b.ddot", 7);
        assertEquals(loc, DdotGoto.parse(DdotGoto.link(loc).substring(DdotGoto.LINK_PREFIX.length())));
    }
}
