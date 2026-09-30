package com.calpano.ddot.refactoring;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.Segment;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.refactoring.BaseRefactoringProcessor;
import com.intellij.usageView.UsageInfo;
import com.intellij.usageView.UsageViewDescriptor;
import com.intellij.usages.Usage;
import com.intellij.usages.UsageInfo2UsageAdapter;
import com.intellij.usages.UsageView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Project-wide rename of a ddot.it entity (feature-rename.adoc). Exact matches are
 * regular usages; case variants are marked as "non-code" usages so the preview lists
 * them in a group of their own, which starts excluded unless the dialog's checkbox
 * was checked. Edits go straight to the host documents, so embedded blocks are
 * renamed in their Markdown/AsciiDoc file.
 */
public final class DdotRenameProcessor extends BaseRefactoringProcessor {

    private final String oldName;
    private final String newName;
    private final boolean includeCaseVariants;
    private final @Nullable PsiElement origin;
    /** Every case variant found, for the result notification. */
    private final Map<String, Integer> variantsFound = new TreeMap<>();

    public DdotRenameProcessor(@NotNull Project project, @Nullable PsiElement origin, @NotNull String oldName,
                               @NotNull String newName, boolean includeCaseVariants) {
        super(project);
        this.origin = origin;
        this.oldName = oldName;
        this.newName = newName;
        this.includeCaseVariants = includeCaseVariants;
    }

    @Override
    protected @NotNull UsageViewDescriptor createUsageViewDescriptor(UsageInfo @NotNull [] usages) {
        return new UsageViewDescriptor() {
            @Override
            public PsiElement @NotNull [] getElements() {
                return origin != null ? new PsiElement[]{origin} : PsiElement.EMPTY_ARRAY;
            }

            @Override
            public String getProcessedElementsHeader() {
                return "Entity to rename to '" + newName + "'";
            }

            @Override
            public @NotNull String getCodeReferencesText(int usagesCount, int filesCount) {
                return "Exact matches of '" + oldName + "' " + counts(usagesCount, filesCount);
            }

            @Override
            public String getCommentReferencesText(int usagesCount, int filesCount) {
                return "Case variants (not renamed unless checked) " + counts(usagesCount, filesCount);
            }
        };
    }

    private static String counts(int usages, int files) {
        return "(" + usages + (usages == 1 ? " occurrence" : " occurrences") + " in "
                + files + (files == 1 ? " file)" : " files)");
    }

    @Override
    protected UsageInfo @NotNull [] findUsages() {
        variantsFound.clear();
        PsiManager psiManager = PsiManager.getInstance(myProject);
        List<UsageInfo> usages = new ArrayList<>();
        for (DdotOccurrences.FileOccurrence fo : DdotOccurrences.inProject(myProject, oldName)) {
            PsiFile file = psiManager.findFile(fo.file());
            if (file == null) continue;
            DdotOccurrences.Occurrence o = fo.occurrence();
            boolean variant = !o.name().equals(oldName);
            if (variant) variantsFound.merge(o.name(), 1, Integer::sum);
            usages.add(new UsageInfo(file, o.start(), o.end(), variant));
        }
        return usages.toArray(UsageInfo.EMPTY_ARRAY);
    }

    /** Without a preview, the dialog's checkbox alone decides about case variants. */
    @Override
    protected boolean preprocessUsages(@NotNull Ref<UsageInfo[]> refUsages) {
        if (!isPreviewUsages() && !includeCaseVariants) {
            refUsages.set(java.util.Arrays.stream(refUsages.get())
                    .filter(u -> !u.isNonCodeUsage).toArray(UsageInfo[]::new));
        }
        return true;
    }

    /** In the preview, case variants start excluded unless the checkbox was checked. */
    @Override
    protected void customizeUsagesView(@NotNull UsageViewDescriptor viewDescriptor, @NotNull UsageView usageView) {
        super.customizeUsagesView(viewDescriptor, usageView);
        if (includeCaseVariants) return;
        List<Usage> variants = new ArrayList<>();
        for (Usage usage : usageView.getUsages()) {
            if (usage instanceof UsageInfo2UsageAdapter adapter && adapter.getUsageInfo().isNonCodeUsage) {
                variants.add(usage);
            }
        }
        usageView.excludeUsages(variants.toArray(Usage.EMPTY_ARRAY));
    }

    @Override
    protected void performRefactoring(UsageInfo @NotNull [] usages) {
        PsiDocumentManager pdm = PsiDocumentManager.getInstance(myProject);
        Map<Document, List<TextRange>> byDocument = new HashMap<>();
        Map<String, Integer> variantsRenamed = new TreeMap<>();
        int exact = 0;
        for (UsageInfo usage : usages) {
            PsiFile file = usage.getFile();
            Segment segment = usage.getSegment();
            TextRange range = segment == null ? null : TextRange.create(segment);
            Document doc = file == null ? null : pdm.getDocument(file);
            if (doc == null || range == null || range.getEndOffset() > doc.getTextLength()) continue;
            String current = doc.getText(range);
            if (!current.equalsIgnoreCase(oldName)) continue; // edited since the search
            byDocument.computeIfAbsent(doc, d -> new ArrayList<>()).add(range);
            if (current.equals(oldName)) exact++;
            else variantsRenamed.merge(current, 1, Integer::sum);
        }
        for (Map.Entry<Document, List<TextRange>> e : byDocument.entrySet()) {
            List<TextRange> ranges = e.getValue();
            ranges.sort(Comparator.comparingInt(TextRange::getStartOffset).reversed());
            for (TextRange r : ranges) e.getKey().replaceString(r.getStartOffset(), r.getEndOffset(), newName);
            pdm.commitDocument(e.getKey());
        }
        notifyResult(exact, variantsRenamed);
    }

    private void notifyResult(int exact, Map<String, Integer> variantsRenamed) {
        if (variantsFound.isEmpty()) return;
        int renamedVariants = variantsRenamed.values().stream().mapToInt(Integer::intValue).sum();
        Map<String, Integer> left = new TreeMap<>(variantsFound);
        variantsRenamed.forEach((spelling, n) -> left.merge(spelling, -n, Integer::sum));
        left.values().removeIf(n -> n <= 0);

        StringBuilder msg = new StringBuilder("Renamed '").append(oldName).append("' to '").append(newName)
                .append("' in ").append(exact + renamedVariants).append(exact + renamedVariants == 1 ? " place" : " places");
        if (renamedVariants > 0) {
            msg.append(", including ").append(renamedVariants).append(renamedVariants == 1 ? " case variant" : " case variants");
        }
        msg.append('.');
        if (!left.isEmpty()) {
            int n = left.values().stream().mapToInt(Integer::intValue).sum();
            msg.append(' ').append(n).append(n == 1 ? " case variant was" : " case variants were")
                    .append(" left unchanged: ").append(spellings(left)).append('.');
        }
        Notifications.Bus.notify(new Notification("ddot.it", "ddot.it rename", msg.toString(),
                left.isEmpty() ? NotificationType.INFORMATION : NotificationType.WARNING), myProject);
    }

    /** {@code 'project eagle' (2), 'PROJECT EAGLE' (1)} */
    static @NotNull String spellings(@NotNull Map<String, Integer> counts) {
        StringBuilder sb = new StringBuilder();
        counts.forEach((spelling, n) -> {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append('\'').append(spelling).append("' (").append(n).append(')');
        });
        return sb.toString();
    }

    @Override
    protected @NotNull String getCommandName() {
        return "Rename '" + oldName + "' to '" + newName + "'";
    }
}
