package com.calpano.ddot.actions;

import com.calpano.ddot.psi.DdotFile;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.Nullable;

final class DdotActionUtil {
    private DdotActionUtil() {}

    static @Nullable DdotFile activeDdotFile(AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return null;
        var editor = FileEditorManager.getInstance(project).getSelectedEditor();
        if (editor == null) return null;
        var vfile = editor.getFile();
        if (vfile == null) return null;
        PsiFile psiFile = PsiManager.getInstance(project).findFile(vfile);
        return psiFile instanceof DdotFile df ? df : null;
    }

    static void enableForDdotFile(AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(activeDdotFile(e) != null
                || e.getData(CommonDataKeys.PSI_FILE) instanceof DdotFile);
    }

    static ActionUpdateThread updateThread() {
        return ActionUpdateThread.BGT;
    }
}
