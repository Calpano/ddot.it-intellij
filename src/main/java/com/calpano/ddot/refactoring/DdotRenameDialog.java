package com.calpano.ddot.refactoring;

import com.calpano.ddot.completion.DdotKgraphService;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.refactoring.ui.RefactoringDialog;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.event.DocumentEvent;
import java.util.Map;

/**
 * The rename dialog of feature-rename.adoc: new name, the case-variant notice and
 * checkbox (only when case variants exist), and a warning when the new name already
 * exists and the two entities would merge.
 */
final class DdotRenameDialog extends RefactoringDialog {

    private final @Nullable PsiElement origin;
    private final String oldName;
    private final Map<String, Integer> caseVariants;
    private final JBTextField nameField;
    private final JBCheckBox variantsBox = new JBCheckBox("Also rename these case variants", false);
    private final JBLabel mergeWarning = new JBLabel();

    DdotRenameDialog(@NotNull Project project, @Nullable PsiElement origin, @NotNull String oldName,
                     @NotNull Map<String, Integer> caseVariants) {
        super(project, true);
        this.origin = origin;
        this.oldName = oldName;
        this.caseVariants = caseVariants;
        this.nameField = new JBTextField(oldName, 30);
        nameField.selectAll();
        nameField.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                updateMergeWarning();
                validateButtons();
            }
        });
        variantsBox.addActionListener(e -> validateButtons());
        mergeWarning.setForeground(JBColor.ORANGE.darker());
        mergeWarning.setBorder(JBUI.Borders.emptyTop(8));
        setTitle("Rename ddot.it Entity");
        init();
        updateMergeWarning();
        validateButtons();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        FormBuilder form = FormBuilder.createFormBuilder()
                .addLabeledComponent("Rename '" + oldName + "' and its usages in the project to:", nameField, true);
        if (!caseVariants.isEmpty()) {
            int n = caseVariants.values().stream().mapToInt(Integer::intValue).sum();
            JBLabel notice = new JBLabel("<html>Also found " + n + (n == 1 ? " occurrence" : " occurrences")
                    + " that differ only in letter case:<br>" + escape(DdotRenameProcessor.spellings(caseVariants))
                    + "</html>");
            notice.setBorder(JBUI.Borders.emptyTop(8));
            form.addComponent(notice).addComponent(variantsBox);
            JBLabel hint = new JBLabel("Unchecked, case variants are left exactly as they are.");
            hint.setForeground(UIUtil.getContextHelpForeground());
            form.addComponent(hint);
        }
        return form.addComponent(mergeWarning).getPanel();
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return nameField;
    }

    private @NotNull String newName() {
        return nameField.getText().strip();
    }

    private void updateMergeWarning() {
        String name = newName();
        int triples = name.equals(oldName) || DdotNameRules.problem(name) != null
                ? 0 : DdotKgraphService.getInstance(myProject).tripleCount(name);
        mergeWarning.setText(triples == 0 ? "" : "<html>'" + escape(name) + "' already exists (" + triples
                + (triples == 1 ? " triple" : " triples") + ").<br>The two will be merged into one entity; after saving, "
                + "the merge cannot be told apart from the original data.</html>");
    }

    @Override
    protected void canRun() throws ConfigurationException {
        String name = newName();
        String problem = DdotNameRules.problem(name);
        if (problem != null) throw new ConfigurationException(problem);
        if (name.equals(oldName) && !variantsBox.isSelected()) throw new ConfigurationException("Enter a new name.");
    }

    @Override
    protected void doAction() {
        invokeRefactoring(new DdotRenameProcessor(myProject, origin, oldName, newName(), variantsBox.isSelected()));
    }

    @Override
    protected boolean hasHelpAction() {
        return false;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
