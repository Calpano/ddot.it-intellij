package com.calpano.ddot.actions;

import com.calpano.ddot.cj.CjToDdot;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Locale;

/**
 * Converts a Connected JSON (CJ) file into a sibling ddot.it file. The mapping is
 * graphinout's (see {@link CjToDdot}); this action only handles file I/O and the
 * IDE plumbing. Offered on the project-view right-click menu for {@code *.cj.json}
 * files and under Tools &gt; ddot.it.
 */
public final class ConvertCjToDdotAction extends AnAction {

    private static final String CJ_SUFFIX = ".cj.json";

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile vf = e.getData(CommonDataKeys.VIRTUAL_FILE);
        e.getPresentation().setEnabledAndVisible(isCjFile(vf));
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile vf = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || !isCjFile(vf)) return;

        VirtualFile parent = vf.getParent();
        if (parent == null) {
            notifyError(project, "Cannot convert: " + vf.getName() + " has no parent directory");
            return;
        }

        // Prefer the in-editor document so unsaved edits are converted too.
        String cjJson = readContent(vf);

        String ddot;
        try {
            ddot = CjToDdot.convert(vf.getName(), cjJson);
        } catch (IOException ex) {
            notifyError(project, "Not valid Connected JSON (" + vf.getName() + "): " + ex.getMessage());
            return;
        } catch (RuntimeException ex) {
            notifyError(project, "Conversion failed for " + vf.getName() + ": " + ex.getMessage());
            return;
        }

        String outName = stripCjSuffix(vf.getName()) + ".ddot";
        WriteCommandAction.runWriteCommandAction(project, "Convert Connected JSON to ddot.it", null, () -> {
            try {
                VirtualFile out = parent.findChild(outName);
                if (out == null) {
                    out = parent.createChildData(this, outName);
                }
                VfsUtil.saveText(out, ddot);
                FileEditorManager.getInstance(project).openFile(out, true);
            } catch (IOException ex) {
                notifyError(project, "Failed to write " + outName + ": " + ex.getMessage());
            }
        });
    }

    private static @NotNull String readContent(@NotNull VirtualFile vf) {
        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc != null) return doc.getText();
        try {
            return VfsUtil.loadText(vf);
        } catch (IOException ex) {
            return "";
        }
    }

    private static boolean isCjFile(@Nullable VirtualFile vf) {
        return vf != null && !vf.isDirectory()
                && vf.getName().toLowerCase(Locale.ROOT).endsWith(CJ_SUFFIX);
    }

    private static String stripCjSuffix(String name) {
        return name.substring(0, name.length() - CJ_SUFFIX.length());
    }

    private static void notifyError(Project project, String message) {
        Notifications.Bus.notify(
                new Notification("ddot.it", "ddot.it", message, NotificationType.ERROR),
                project);
    }
}
