package com.calpano.ddot.structure;

import com.calpano.ddot.psi.DdotEntity;
import com.calpano.ddot.psi.DdotFile;
import com.calpano.ddot.psi.DdotLine;
import com.calpano.ddot.psi.DdotOffRegions;
import com.calpano.ddot.psi.DdotPsiUtil;
import com.intellij.ide.util.treeView.smartTree.TreeElement;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.util.PsiTreeUtil;
import it.ddot.core.DdotSlot;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the Structure view tree: AsciiDoc section headings nest by level, and
 * each triple subject hangs under the section it appears in. Skipped heading
 * levels are ignored — a {@code ===} directly under a {@code =} simply nests one
 * deeper.
 */
final class DdotOutline {

    /** {@code = Title}, {@code == Title}, … with an optional symmetric closing {@code ==}. */
    private static final Pattern HEADING = Pattern.compile("^(=+)[ \\t]+(\\S.*?)(?:[ \\t]+\\1)?[ \\t]*$");

    private DdotOutline() {}

    static @NotNull List<TreeElement> build(@NotNull DdotFile file) {
        DdotOffRegions.Result regions = DdotOffRegions.regionsFor(file);
        Document doc = PsiDocumentManager.getInstance(file.getProject()).getDocument(file);

        List<TreeElement> roots = new ArrayList<>();
        Deque<DdotSectionElement> open = new ArrayDeque<>();
        // Subjects are deduplicated per section (null key = before the first heading).
        Map<DdotSectionElement, Set<String>> seen = new HashMap<>();

        for (DdotLine line : PsiTreeUtil.getChildrenOfTypeAsList(file, DdotLine.class)) {
            if (doc != null && regions.isInactive(doc.getLineNumber(line.getTextRange().getStartOffset()))) continue;

            DdotSectionElement section = parseHeading(line);
            if (section != null) {
                while (!open.isEmpty() && open.peek().getLevel() >= section.getLevel()) open.pop();
                (open.isEmpty() ? roots : open.peek().children).add(section);
                open.push(section);
                continue;
            }

            DdotSectionElement current = open.peek();
            Set<String> names = seen.computeIfAbsent(current, k -> new HashSet<>());
            for (DdotEntity e : PsiTreeUtil.getChildrenOfTypeAsList(line, DdotEntity.class)) {
                if (e.getRole() != DdotSlot.SUBJECT) continue;
                // Subject only counts when the line actually opens a triple — i.e. it has
                // at least one separator. Otherwise free-form lines land in the outline.
                if (!DdotPsiUtil.isOnTripleLine(e)) continue;
                String name = e.getName();
                if (name == null || name.isEmpty()) continue;
                if (!names.add(name)) continue;
                (current == null ? roots : current.children).add(new DdotStructureViewElement(e));
            }
        }
        return roots;
    }

    private static @Nullable DdotSectionElement parseHeading(@NotNull DdotLine line) {
        Matcher m = HEADING.matcher(line.getText().strip());
        if (!m.matches() || !line.getText().startsWith("=")) return null;
        return new DdotSectionElement(line, m.group(2), m.group(1).length());
    }
}
