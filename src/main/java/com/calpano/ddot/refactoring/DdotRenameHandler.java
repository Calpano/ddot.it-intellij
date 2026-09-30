package com.calpano.ddot.refactoring;

import com.calpano.ddot.psi.DdotEntity;
import com.intellij.injected.editor.EditorWindow;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.refactoring.rename.RenameHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Shift+F6 / Refactor → Rename on a ddot.it entity — in a {@code .ddot} file, in an
 * embedded block, or on a Structure view node — opens {@link DdotRenameDialog}.
 * The entity under the caret is found with the same tokenizer-based search the
 * rename itself uses, in the host file, so embedded blocks need no special case.
 */
public final class DdotRenameHandler implements RenameHandler {

    private record Target(@NotNull String name, @Nullable PsiElement origin) {}

    @Override
    public boolean isAvailableOnDataContext(@NotNull DataContext dataContext) {
        return target(dataContext) != null;
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file, DataContext dataContext) {
        Target t = target(dataContext);
        if (t != null) start(project, t.name(), t.origin());
    }

    @Override
    public void invoke(@NotNull Project project, PsiElement @NotNull [] elements, DataContext dataContext) {
        Target t = target(dataContext);
        if (t != null) start(project, t.name(), t.origin());
    }

    /** Open the rename dialog for {@code name}, after looking up its case variants. */
    public static void start(@NotNull Project project, @NotNull String name, @Nullable PsiElement origin) {
        List<DdotOccurrences.FileOccurrence> found = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                () -> ReadAction.compute(() -> DdotOccurrences.inProject(project, name)),
                "Searching for '" + name + "'", true, project);
        if (found == null) return; // cancelled
        Map<String, Integer> variants = new TreeMap<>();
        for (DdotOccurrences.FileOccurrence fo : found) {
            String spelling = fo.occurrence().name();
            if (!spelling.equals(name)) variants.merge(spelling, 1, Integer::sum);
        }
        new DdotRenameDialog(project, origin, name, variants).show();
    }

    private static @Nullable Target target(@NotNull DataContext dataContext) {
        Editor editor = CommonDataKeys.EDITOR.getData(dataContext);
        if (editor != null) return targetInEditor(editor);
        PsiElement element = CommonDataKeys.PSI_ELEMENT.getData(dataContext);
        if (element instanceof DdotEntity entity && entity.getName() != null && !entity.getName().isEmpty()) {
            return new Target(entity.getName(), entity);
        }
        return null;
    }

    private static @Nullable Target targetInEditor(@NotNull Editor editor) {
        Project project = editor.getProject();
        if (project == null) return null;
        Editor host = editor;
        int offset = editor.getCaretModel().getOffset();
        if (editor instanceof EditorWindow window) {
            host = window.getDelegate();
            offset = window.getDocument().injectedToHost(offset);
        }
        PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(host.getDocument());
        VirtualFile vf = file == null ? null : file.getVirtualFile();
        if (vf == null) return null;
        DdotOccurrences.Occurrence o = DdotOccurrences.at(vf.getExtension(), host.getDocument().getText(), offset);
        if (o == null) return null;
        PsiElement leaf = file.findElementAt(Math.max(0, Math.min(offset, o.end() - 1)));
        return new Target(o.name(), PsiTreeUtil.getParentOfType(leaf, DdotEntity.class, false));
    }
}
