package com.calpano.ddot.completion;

import com.calpano.ddot.psi.DdotFile;
import com.calpano.ddot.psi.DdotPsiUtil;
import com.calpano.ddot.psi.DdotRole;
import com.intellij.codeInsight.AutoPopupController;
import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.PlainPrefixMatcher;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slot-aware completion for ddot.it. Direct port of the VS Code provider:
 * <ul>
 *   <li>subject slot → known subjects;</li>
 *   <li>predicate slot → known predicates (closes with {@code .. });</li>
 *   <li>object slot → known objects;</li>
 *   <li>after {@code ..} or on a fresh continuation line → {@code ..rel..} predicates;</li>
 *   <li>mid-separator typing ({@code .} or {@code ,}) → snippet completion of {@code ..}, {@code ....}, {@code ,,}.</li>
 * </ul>
 * Prefix matching is plain prefix (case-insensitive), not the IDE default fuzzy/subsequence
 * matcher — same reason as VS Code: aggressive matching surfaces unrelated entities while
 * the user is typing a brand-new one and ENTER would commit the wrong thing.
 */
public final class DdotCompletionContributor extends CompletionContributor {

    private static final Pattern SEP = Pattern.compile("\\.{4}|\\.{2}");
    private static final Pattern SEP_OR_META = Pattern.compile("\\.{4}|\\.{2}|,,|::");
    private static final Pattern STRAY_THREE_DOTS = Pattern.compile("(^|[^.])\\.{3}$");

    @Override
    public void fillCompletionVariants(@NotNull CompletionParameters parameters,
                                       @NotNull CompletionResultSet result) {
        if (!(parameters.getOriginalFile() instanceof DdotFile file)) return;

        Editor editor = parameters.getEditor();
        Document doc = editor.getDocument();
        int offset = parameters.getOffset();
        int lineNum = doc.getLineNumber(offset);
        int lineStart = doc.getLineStartOffset(lineNum);
        String beforeCursor = doc.getCharsSequence().subSequence(lineStart, offset).toString();

        // Stray 3-dot run — only `..` and `....` are valid; hide the menu.
        if (STRAY_THREE_DOTS.matcher(beforeCursor).find()) return;

        // Where the entity-in-progress starts (after last separator, skipping whitespace).
        int entityStartCol = entityStartColumn(beforeCursor);
        String prefix = beforeCursor.substring(entityStartCol);
        DdotRole role = roleAtCursor(beforeCursor);
        boolean inMeta = beforeCursor.contains(",,");

        CompletionResultSet results = result.withPrefixMatcher(new PlainPrefixMatcher(prefix, true));

        // Separator snippets — only when the user is mid-typing a separator
        // (current segment ends in a `.` or `,` run).
        Matcher trail = Pattern.compile("[.,]+$").matcher(prefix);
        if (trail.find()) {
            int trailLen = trail.group().length();
            int snippetReplaceStart = lineStart + entityStartCol + (prefix.length() - trailLen);
            String[] labels = inMeta ? new String[]{"..", ",,"} : separatorsForRole(role);
            for (String label : labels) {
                results.addElement(separatorElement(label, snippetReplaceStart));
            }
        }

        // After-`..` predicate suggestions: cursor right after `..` (not part of `....`),
        // emit `..rel..` items whose range absorbs the existing `..`.
        Integer afterDotDot = (role == DdotRole.PREDICATE && prefix.isEmpty())
                ? findPrecedingDotDotColumn(beforeCursor) : null;

        if (role != null && (afterDotDot == null || !prefix.isEmpty())) {
            for (String entity : DdotPsiUtil.namesByRole(file, role)) {
                if (entity.equals(prefix)) continue;
                results.addElement(entityElement(entity, role, lineStart + entityStartCol));
            }
        }

        if (afterDotDot != null) {
            int replaceStart = lineStart + afterDotDot;
            for (String pred : DdotPsiUtil.namesByRole(file, DdotRole.PREDICATE)) {
                results.addElement(continuationPredicateElement(pred, replaceStart));
            }
        }

        // Fresh line in continuation context: surface `..rel..` predicates.
        if (prefix.isEmpty() && beforeCursor.isBlank() && DdotContext.isContinuationContext(doc, lineNum)) {
            int replaceStart = lineStart + entityStartCol;
            for (String pred : DdotPsiUtil.namesByRole(file, DdotRole.PREDICATE)) {
                results.addElement(continuationPredicateElement(pred, replaceStart));
            }
        }
    }

    private static int entityStartColumn(String beforeCursor) {
        Matcher m = SEP_OR_META.matcher(beforeCursor);
        int lastSepEnd = 0;
        while (m.find()) lastSepEnd = m.end();
        int p = lastSepEnd;
        while (p < beforeCursor.length() && Character.isWhitespace(beforeCursor.charAt(p))) p++;
        return p;
    }

    private static @Nullable DdotRole roleAtCursor(String beforeCursor) {
        if (beforeCursor.contains(",,")) return DdotRole.META;
        int slot = 0;
        Matcher m = SEP.matcher(beforeCursor);
        while (m.find()) slot += m.group().length() == 4 ? 2 : 1;
        return switch (Math.min(slot, 2)) {
            case 0 -> DdotRole.SUBJECT;
            case 1 -> DdotRole.PREDICATE;
            default -> DdotRole.OBJECT;
        };
    }

    private static String[] separatorsForRole(@Nullable DdotRole role) {
        if (role == null) return new String[]{"..", ",,"};
        return switch (role) {
            case SUBJECT -> new String[]{"..", "...."};
            case PREDICATE -> new String[]{".."};
            case OBJECT -> new String[]{",,"};
            case META -> new String[]{"..", ",,"};
        };
    }

    /** Column of an isolated {@code ..} immediately preceding the cursor (allowing trailing
     *  whitespace), or {@code null} if the cursor isn't in that state or the {@code ..} is
     *  part of a {@code ....}. */
    private static @Nullable Integer findPrecedingDotDotColumn(String beforeCursor) {
        int end = beforeCursor.length();
        while (end > 0 && Character.isWhitespace(beforeCursor.charAt(end - 1))) end--;
        if (end < 2 || !beforeCursor.startsWith("..", end - 2)) return null;
        if (end >= 3 && beforeCursor.charAt(end - 3) == '.') return null;
        return end - 2;
    }


    // Lookup elements --------------------------------------------------------

    private static LookupElement separatorElement(String label, int replaceStart) {
        Map<String, String> details = Map.of(
                "..", "Typed link separator",
                "....", "Simple link separator",
                ",,", "Metadata separator");
        return LookupElementBuilder.create(label)
                .withTypeText(details.getOrDefault(label, ""))
                .withIcon(AllIcons.Nodes.Static)
                .withInsertHandler((ctx, item) -> {
                    Document doc = ctx.getDocument();
                    doc.replaceString(replaceStart, ctx.getTailOffset(), label);
                    ctx.getEditor().getCaretModel().moveToOffset(replaceStart + label.length());
                });
    }

    private static LookupElement entityElement(String entity, DdotRole role, int replaceStart) {
        String roleLabel = switch (role) {
            case SUBJECT -> "Subject";
            case PREDICATE -> "Predicate";
            case OBJECT -> "Object";
            case META -> "Metadata";
        };
        return LookupElementBuilder.create(entity)
                .withTypeText(roleLabel + " from this document")
                .withIcon(AllIcons.Nodes.Variable)
                .withInsertHandler((ctx, item) -> {
                    Document doc = ctx.getDocument();
                    String inserted = switch (role) {
                        case SUBJECT -> entity + " ..";
                        case PREDICATE -> entity + ".. ";
                        case OBJECT -> entity + "\n";
                        case META -> entity;
                    };
                    doc.replaceString(replaceStart, ctx.getTailOffset(), inserted);
                    int newCaret = replaceStart + inserted.length();
                    ctx.getEditor().getCaretModel().moveToOffset(newCaret);
                    if (role == DdotRole.SUBJECT || role == DdotRole.PREDICATE) {
                        AutoPopupController.getInstance(ctx.getProject())
                                .scheduleAutoPopup(ctx.getEditor());
                    }
                });
    }

    private static LookupElement continuationPredicateElement(String pred, int replaceStart) {
        String label = "..%s..".formatted(pred);
        String inserted = "..%s.. ".formatted(pred);
        return LookupElementBuilder.create(label)
                .withTypeText("Continuation predicate")
                .withIcon(AllIcons.Nodes.Variable)
                .withInsertHandler((ctx, item) -> {
                    Document doc = ctx.getDocument();
                    doc.replaceString(replaceStart, ctx.getTailOffset(), inserted);
                    ctx.getEditor().getCaretModel().moveToOffset(replaceStart + inserted.length());
                    AutoPopupController.getInstance(ctx.getProject())
                            .scheduleAutoPopup(ctx.getEditor());
                });
    }

}
