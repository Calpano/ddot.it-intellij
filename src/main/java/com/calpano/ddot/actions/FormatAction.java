package com.calpano.ddot.actions;

import com.calpano.ddot.psi.DdotFile;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiDocumentManager;
import com.calpano.ddot.formatter.DdotFormatter;
import org.jetbrains.annotations.NotNull;

public final class FormatAction extends AnAction {
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
        if (file == null) return;
        Document doc = PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
        if (doc == null) return;

        String input = doc.getText();
        String output = DdotFormatter.formatDocument(input);
        if (output.equals(input)) return;

        WriteCommandAction.runWriteCommandAction(file.getProject(), "Format ddot.it Document",
                null, () -> doc.setText(output), file);
    }
}
