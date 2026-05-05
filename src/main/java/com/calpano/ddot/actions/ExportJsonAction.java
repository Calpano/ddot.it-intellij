package com.calpano.ddot.actions;

import com.calpano.ddot.export.DdotEvent;
import com.calpano.ddot.export.DdotEventExporter;
import com.calpano.ddot.psi.DdotFile;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;

public final class ExportJsonAction extends AnAction {
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return DdotActionUtil.updateThread();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        DdotActionUtil.enableForDdotFile(e);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        DdotFile file = DdotActionUtil.activeDdotFile(e);
        Project project = e.getProject();
        if (file == null || project == null) return;

        VirtualFile sourceVf = file.getVirtualFile();
        if (sourceVf == null || sourceVf.getParent() == null) {
            notifyError(project, "Cannot export: source file has no on-disk location");
            return;
        }
        VirtualFile parent = sourceVf.getParent();

        String source = file.getName();
        String kind = file.getLanguage().getID();
        List<DdotEvent> events = DdotEventExporter.parse(file.getText(), kind, source);
        String jsonl = DdotEventExporter.toJsonl(events);
        String outName = stripExtension(source) + ".jsonl";

        WriteCommandAction.runWriteCommandAction(project, "Export ddot.it as JSON", null, () -> {
            try {
                VirtualFile out = parent.findChild(outName);
                if (out == null) {
                    out = parent.createChildData(this, outName);
                }
                VfsUtil.saveText(out, jsonl);
                FileEditorManager.getInstance(project).openFile(out, true);
            } catch (IOException ex) {
                notifyError(project, "Failed to write " + outName + ": " + ex.getMessage());
            }
        });
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static void notifyError(Project project, String message) {
        Notifications.Bus.notify(
                new Notification("ddot.it", "ddot.it", message, NotificationType.ERROR),
                project);
    }
}
