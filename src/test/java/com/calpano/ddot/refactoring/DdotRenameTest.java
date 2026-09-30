package com.calpano.ddot.refactoring;

import com.intellij.notification.Notification;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.ui.TestDialog;
import com.intellij.openapi.ui.TestDialogManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.refactoring.rename.RenameHandler;
import com.intellij.refactoring.rename.RenameHandlerRegistry;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.usageView.UsageInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Acceptance tests of feature-rename.adoc. */
public final class DdotRenameTest extends BasePlatformTestCase {

    private VirtualFile ddot;
    private VirtualFile md;
    private VirtualFile adoc;
    private final List<String> notifications = new ArrayList<>();

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        ddot = myFixture.addFileToProject("a.ddot", String.join("\n",
                "= Project Eagle overview",
                "Project Eagle ..started in.. 2024",
                "..doc site.. example.com",
                "John Doe ..leads.. Project Eagle ,, ..since.. Project Eagle",
                "Project Eagle II ..is.. other",
                "project eagle ..is.. lower",
                "ddot.it/off",
                "Project Eagle ..is.. hidden",
                "ddot.it/on",
                "doc ..body.. !!block?end=END",
                "Project Eagle ..is.. verbatim",
                "END", "")).getVirtualFile();
        md = myFixture.addFileToProject("b.md", String.join("\n",
                "# Project Eagle",
                "```ddot",
                "Mary ..likes.. Project Eagle",
                "PROJECT EAGLE ..is.. loud",
                "```", "")).getVirtualFile();
        adoc = myFixture.addFileToProject("c.adoc", String.join("\n",
                "= Doc",
                "[source,ddot]",
                "----",
                "Project Eagle ..Project Eagle.. yes",
                "----", "")).getVirtualFile();
        getProject().getMessageBus().connect(getTestRootDisposable()).subscribe(Notifications.TOPIC, new Notifications() {
            @Override
            public void notify(Notification notification) {
                notifications.add(notification.getContent());
            }
        });
    }

    private static String text(VirtualFile file) {
        return FileDocumentManager.getInstance().getDocument(file).getText();
    }

    public void testExactMatchesInEveryRoleAndFileAreRenamed() {
        new DdotRenameProcessor(getProject(), null, "Project Eagle", "Eagle", false).run();

        assertEquals(String.join("\n",
                "= Project Eagle overview",                    // prose: untouched
                "Eagle ..started in.. 2024",
                "..doc site.. example.com",                    // inherits the subject: nothing to change
                "John Doe ..leads.. Eagle ,, ..since.. Eagle", // object and metadata value
                "Project Eagle II ..is.. other",               // no partial match
                "project eagle ..is.. lower",                  // case variant: left
                "ddot.it/off",
                "Project Eagle ..is.. hidden",                 // off region
                "ddot.it/on",
                "doc ..body.. !!block?end=END",
                "Project Eagle ..is.. verbatim",               // block body
                "END", ""), text(ddot));
        assertEquals(String.join("\n",
                "# Project Eagle",
                "```ddot",
                "Mary ..likes.. Eagle",
                "PROJECT EAGLE ..is.. loud",
                "```", ""), text(md));
        assertTrue(text(adoc), text(adoc).contains("Eagle ..Eagle.. yes"));    // subject and relation

        assertEquals(1, notifications.size());
        assertEquals("Renamed 'Project Eagle' to 'Eagle' in 6 places. 2 case variants were left unchanged: "
                + "'PROJECT EAGLE' (1), 'project eagle' (1).", notifications.getFirst());
    }

    public void testCheckedCaseVariantsAreRenamedAsTyped() {
        new DdotRenameProcessor(getProject(), null, "Project Eagle", "Eagle", true).run();
        assertTrue(text(ddot), text(ddot).contains("\nEagle ..is.. lower\n"));
        assertTrue(text(md), text(md).contains("\nEagle ..is.. loud\n"));
        assertEquals("Renamed 'Project Eagle' to 'Eagle' in 8 places, including 2 case variants.",
                notifications.getFirst());
    }

    public void testSameNameWithVariantsNormalizesSpelling() {
        new DdotRenameProcessor(getProject(), null, "Project Eagle", "Project Eagle", true).run();
        assertFalse(text(ddot).contains("project eagle ..is"));
        assertFalse(text(md).contains("PROJECT EAGLE"));
    }

    public void testNoNotificationWithoutCaseVariants() {
        new DdotRenameProcessor(getProject(), null, "John Doe", "Jane Doe", false).run();
        assertTrue(text(ddot).contains("Jane Doe ..leads.."));
        assertEmpty(notifications);
    }

    public void testRelationAndMetadataKeyAreRenamed() {
        new DdotRenameProcessor(getProject(), null, "since", "from", false).run();
        assertTrue(text(ddot), text(ddot).contains(",, ..from.. Project Eagle"));
        new DdotRenameProcessor(getProject(), null, "leads", "heads", false).run();
        assertTrue(text(ddot), text(ddot).contains("John Doe ..heads.."));
    }

    public void testOneUndoRevertsEveryFile() {
        String before = text(ddot) + text(md) + text(adoc);
        myFixture.openFileInEditor(ddot);
        new DdotRenameProcessor(getProject(), null, "Project Eagle", "Eagle", false).run();
        assertFalse(before.equals(text(ddot) + text(md) + text(adoc)));
        // A multi-file rename is one global command; IntelliJ confirms before undoing it.
        TestDialog previous = TestDialogManager.setTestDialog(TestDialog.OK);
        try {
            UndoManager.getInstance(getProject()).undo(null);
        } finally {
            TestDialogManager.setTestDialog(previous);
        }
        assertEquals(before, text(ddot) + text(md) + text(adoc));
    }

    public void testShiftF6UsesOnlyTheDdotHandler() {
        myFixture.configureFromExistingVirtualFile(ddot);
        myFixture.getEditor().getCaretModel().moveToOffset(text(ddot).indexOf("John Doe") + 2);
        PsiFile file = myFixture.getFile();
        PsiElement element = file.findElementAt(myFixture.getCaretOffset());
        var context = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, getProject())
                .add(CommonDataKeys.EDITOR, myFixture.getEditor())
                .add(CommonDataKeys.PSI_FILE, file)
                .add(CommonDataKeys.PSI_ELEMENT, element.getParent())
                .build();
        List<? extends RenameHandler> handlers = RenameHandlerRegistry.getInstance().getRenameHandlers(context);
        assertEquals(handlers.toString(), 1, handlers.size());
        assertInstanceOf(handlers.getFirst(), DdotRenameHandler.class);
    }

    public void testShiftF6InsideMarkdownFenceButNotOnProse() {
        myFixture.configureFromExistingVirtualFile(md);
        String t = text(md);
        myFixture.getEditor().getCaretModel().moveToOffset(t.indexOf("Mary") + 1);
        assertTrue(new DdotRenameHandler().isAvailableOnDataContext(editorContext()));
        myFixture.getEditor().getCaretModel().moveToOffset(t.indexOf("# Project") + 4);
        assertFalse(new DdotRenameHandler().isAvailableOnDataContext(editorContext()));
    }

    public void testFindUsagesMatchesTheRenameSearch() {
        myFixture.configureFromExistingVirtualFile(ddot);
        myFixture.getEditor().getCaretModel().moveToOffset(text(ddot).indexOf("Project Eagle ..started") + 1);
        PsiElement entity = myFixture.getFile().findElementAt(myFixture.getCaretOffset()).getParent();
        Collection<UsageInfo> usages = myFixture.findUsages(entity);
        long exact = usages.stream().filter(u -> !u.isNonCodeUsage).count();
        long variants = usages.stream().filter(u -> u.isNonCodeUsage).count();
        assertEquals(6, exact);
        assertEquals(2, variants);
    }

    public void testNameRules() {
        assertNull(DdotNameRules.problem("Project Eagle"));
        assertNull(DdotNameRules.problem("example.com/x"));
        assertNotNull(DdotNameRules.problem(""));
        assertNotNull(DdotNameRules.problem("a\nb"));
        assertNotNull(DdotNameRules.problem("a..b"));
        assertNotNull(DdotNameRules.problem("a,,b"));
        assertNotNull(DdotNameRules.problem("a::b"));
        assertNotNull(DdotNameRules.problem(".a"));
        assertNotNull(DdotNameRules.problem("a."));
        assertNotNull(DdotNameRules.problem("!!this"));
        assertNotNull(DdotNameRules.problem("ddot.it/x"));
        assertNotNull(DdotNameRules.problem("https://ddot.it/x"));
    }

    private com.intellij.openapi.actionSystem.DataContext editorContext() {
        return SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, getProject())
                .add(CommonDataKeys.EDITOR, myFixture.getEditor())
                .add(CommonDataKeys.PSI_FILE, myFixture.getFile())
                .build();
    }
}
