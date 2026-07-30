package com.calpano.ddot.psi;

import com.calpano.ddot.it.token.DdotRole;
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
     * True iff the entity sits on a line that actually opens a triple. This is the
     * line GATE of the Highlighting How-To (§D5): a line is a ddot.it triple only
     * when it carries a complete operator skeleton — <em>two</em> {@code ..} (DT2)
     * or one {@code ....} / {@code .. ..} (the untyped operator). A lone {@code ..}
     * in prose does not qualify, so titles, section headers and sentences like
     * {@code A lone .. in prose.} get no role-based behavior (highlighting,
     * structure view).
     * <p>Only the main part of the line is consulted; the meta part after
     * {@code ,,} has its own separators and cannot gate the line by itself.
     */
    public static boolean isOnTripleLine(@NotNull DdotEntity entity) {
        DdotLine line = PsiTreeUtil.getParentOfType(entity, DdotLine.class);
        if (line == null) return false;
        int dt2 = 0;
        for (PsiElement child = line.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof DdotMetadata) break;
            var t = child.getNode().getElementType();
            if (t == DdotTypes.COMMA_COMMA) break;
            if (t == DdotTypes.DOT_DOT_DOT_DOT) return true;
            if (t == DdotTypes.DOT_DOT && ++dt2 == 2) return true;
        }
        return false;
    }
}
