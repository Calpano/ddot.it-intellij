package com.calpano.ddot.documentation;

import com.calpano.ddot.psi.DdotEntity;
import com.calpano.ddot.psi.DdotPsiUtil;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.injected.editor.EditorWindow;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.psi.PsiFile;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.HTMLEditorKitBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.JEditorPane;
import javax.swing.event.HyperlinkEvent;

/**
 * Alt+Enter on a subject, relation or object → the entity popup, shown in a
 * popup of its own. Same content and links as the hover documentation, but it
 * does not depend on the IDE deciding to show a hover.
 */
public final class DdotShowFactsIntention implements IntentionAction {

    private String entityName = "";

    @Override
    public @NotNull String getText() {
        return "Show what the project states about '" + entityName + "'";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Show what the project states about an entity";
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        if (editor == null || file == null) return false;
        DdotEntity entity = DdotEntityFacts.entityAt(file, editor.getCaretModel().getOffset());
        String name = entity == null ? null : entity.getName();
        if (name == null || name.isEmpty() || !DdotPsiUtil.isOnTripleLine(entity)) return false;
        entityName = name;
        return true;
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file) {
        DdotEntity entity = DdotEntityFacts.entityAt(file, editor.getCaretModel().getOffset());
        if (entity == null) return;
        Editor host = editor instanceof EditorWindow window ? window.getDelegate() : editor;
        String html = DdotEntityFacts.html(entity);
        if (html != null) show(project, host, html);
    }

    private static void show(@NotNull Project project, @NotNull Editor editor, @NotNull String html) {
        JEditorPane pane = new JEditorPane();
        pane.setEditorKit(HTMLEditorKitBuilder.simple());
        pane.setEditable(false);
        pane.setBorder(JBUI.Borders.empty(8, 12));
        pane.setText("<html><body>" + html + "</body></html>");
        pane.setCaretPosition(0);

        JBPopup popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(new JBScrollPane(pane), pane)
                .setRequestFocus(true)
                .setResizable(true)
                .setMovable(true)
                .createPopup();
        pane.addHyperlinkListener(e -> {
            if (e.getEventType() != HyperlinkEvent.EventType.ACTIVATED) return;
            popup.cancel();
            DdotTripleNavigator.follow(project, e.getDescription());
        });
        popup.showInBestPositionFor(editor);
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(@NotNull Project project, @NotNull Editor editor,
                                                         @NotNull PsiFile file) {
        return IntentionPreviewInfo.EMPTY;
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }
}
