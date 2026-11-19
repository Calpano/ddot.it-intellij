package com.calpano.ddot.completion;

import com.calpano.ddot.completion.DdotVocabulary.Pool;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.Map;

/**
 * The project graph follows file events the VFS reports only for a directory, or under a file's old name:
 * deleting or renaming a folder, and renaming a file. Light tests share one project, so a link left behind
 * here also shows up in every later test's completion.
 */
public final class DdotKgraphFileEventsTest extends BasePlatformTestCase {

    private Map<String, Integer> subjects() {
        return DdotKgraphService.getInstance(getProject()).vocabulary().counts(Pool.SUBJECT);
    }

    public void testDeletingADirectoryDropsTheFilesInIt() throws Exception {
        VirtualFile file = myFixture.addFileToProject("notes/deep/b.md", "```ddot\nCarol ..likes.. Tea\n```\n")
                .getVirtualFile();
        assertTrue(subjects().containsKey("Carol"));

        VirtualFile notes = file.getParent().getParent();
        WriteAction.runAndWait(() -> notes.delete(this));
        assertFalse("links of a deleted folder's files are gone: " + subjects(), subjects().containsKey("Carol"));
    }

    public void testRenamingADirectoryKeepsItsFilesUnderTheNewName() throws Exception {
        VirtualFile file = myFixture.addFileToProject("docs/c.ddot", "Dave ..owns.. Car\n").getVirtualFile();
        assertEquals(Integer.valueOf(1), subjects().get("Dave"));

        VirtualFile docs = file.getParent();
        WriteAction.runAndWait(() -> docs.rename(this, "papers"));
        // Stated once, under the new path; not twice, under the old one as well.
        assertEquals(Integer.valueOf(1), subjects().get("Dave"));

        WriteAction.runAndWait(() -> docs.delete(this));
        assertFalse(subjects().containsKey("Dave"));
    }

    public void testRenamingAFileAwayFromDdotDropsIt() throws Exception {
        VirtualFile file = myFixture.addFileToProject("e.ddot", "Erin ..knows.. Frank\n").getVirtualFile();
        assertTrue(subjects().containsKey("Erin"));

        WriteAction.runAndWait(() -> file.rename(this, "e.txt"));
        assertFalse("a file renamed to a non-ddot name is no longer read: " + subjects(),
                subjects().containsKey("Erin"));
    }
}
