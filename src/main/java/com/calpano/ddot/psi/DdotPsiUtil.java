package com.calpano.ddot.psi;

import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Positional helpers over the PSI tree.
 * <p>The grammar has no syntactic distinction between subject, predicate and object
 * entities — the role is determined by counting separators that precede the entity
 * within its line. This file is the single source of truth for that mapping.
 */
public final class DdotPsiUtil {
    private DdotPsiUtil() {}

    /** Role of {@code entity} based on its position within its enclosing {@link DdotLine}. */
    public static @NotNull DdotRole getRole(@NotNull DdotEntity entity) {
        // Anything inside a metadata block is META, regardless of separators.
        if (PsiTreeUtil.getParentOfType(entity, DdotMetadata.class) != null) {
            return DdotRole.META;
        }
        DdotLine line = PsiTreeUtil.getParentOfType(entity, DdotLine.class);
        if (line == null) return DdotRole.SUBJECT;

        int slot = 0;
        for (PsiElement child = line.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child == entity) break;
            // A METADATA wrapper would have been hit before reaching the entity, but
            // belt-and-braces in case the entity is somehow on the meta side.
            if (child instanceof DdotMetadata) return DdotRole.META;
            var t = child.getNode().getElementType();
            if (t == DdotTypes.DOT_DOT) slot += 1;
            else if (t == DdotTypes.DOT_DOT_DOT_DOT) slot += 2;
        }
        return slotToRole(slot);
    }

    private static @NotNull DdotRole slotToRole(int slot) {
        return switch (Math.min(slot, 2)) {
            case 0 -> DdotRole.SUBJECT;
            case 1 -> DdotRole.PREDICATE;
            default -> DdotRole.OBJECT;
        };
    }

    /** All entities in {@code file}, in document order. */
    public static @NotNull Collection<DdotEntity> allEntities(@NotNull DdotFile file) {
        return PsiTreeUtil.findChildrenOfType(file, DdotEntity.class);
    }

    /** Entities with the given role, in document order. Multi-word entity text is trimmed. */
    public static @NotNull List<String> namesByRole(@NotNull DdotFile file, @NotNull DdotRole role) {
        List<String> out = new ArrayList<>();
        for (DdotEntity e : allEntities(file)) {
            if (e.getRole() != role) continue;
            String name = e.getName();
            if (name == null || name.isEmpty()) continue;
            if (name.contains("::")) continue;
            out.add(name);
        }
        return out;
    }

    /** Find the line element containing the given offset (or {@code null} if none). */
    public static @Nullable DdotLine lineAtOffset(@NotNull DdotFile file, int offset) {
        PsiElement element = file.findElementAt(offset);
        return element == null ? null : PsiTreeUtil.getParentOfType(element, DdotLine.class);
    }

    /**
     * True iff the entity sits on a line that actually opens a triple — i.e. the line
     * contains at least one {@code ..} or {@code ....} separator. Used to suppress
     * role-based behavior (highlighting, structure view) on free-form text lines like
     * titles or section headers, where every word lexes as a slot-0 "entity" but isn't
     * really part of any triple.
     */
    public static boolean isOnTripleLine(@NotNull DdotEntity entity) {
        DdotLine line = PsiTreeUtil.getParentOfType(entity, DdotLine.class);
        if (line == null) return false;
        for (PsiElement child = line.getFirstChild(); child != null; child = child.getNextSibling()) {
            var t = child.getNode().getElementType();
            if (t == DdotTypes.DOT_DOT || t == DdotTypes.DOT_DOT_DOT_DOT) return true;
        }
        return false;
    }
}
