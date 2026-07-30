package com.calpano.ddot.completion;

import com.calpano.ddot.index.DdotEntityIndex;
import com.calpano.ddot.psi.DdotFile;
import com.calpano.ddot.psi.DdotRole;
import com.intellij.codeInsight.AutoPopupController;
import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionSorter;
import com.intellij.codeInsight.completion.PlainPrefixMatcher;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.codeInsight.lookup.LookupElementWeigher;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slot-aware completion for ddot.it, per the Autocomplete Specification:
 * <ul>
 *   <li>slot detection via the parse automaton ({@link DdotContext}), seeded with
 *       the document state at the start of the caret line;</li>
 *   <li>value completion from the project-wide vocabulary, in the spec's total
 *       deterministic order (the list order of {@link DdotEntityIndex#ranked} plus
 *       the fit boost), pinned end-to-end by a custom sorter;</li>
 *   <li>command completion after any command spelling prefix ({@code !!},
 *       {@code ddot.it/}, {@code http://ddot.it/}, {@code https://ddot.it/});</li>
 *   <li>no completion at all — automatic or explicit — in inert regions
 *       ({@code !!off}…{@code !!on}, {@code !!block} bodies) and meta text.</li>
 * </ul>
 * Prefix matching is plain prefix (case-insensitive), not the IDE default
 * fuzzy/subsequence matcher — the spec forbids substring and fuzzy matching:
 * aggressive matching surfaces unrelated entities while the user is typing a
 * brand-new one and ENTER would commit the wrong thing.
 */
public final class DdotCompletionContributor extends CompletionContributor {

    // Separators with the tokenization-rule lookarounds: a run of three or more
    // dots is ordinary text (`...` is TX, not DT2-and-a-half).
    private static final Pattern SEP =
            Pattern.compile("(?<!\\.)(?:\\.{4}|\\.{2})(?!\\.)");
    private static final Pattern SEP_OR_META =
            Pattern.compile("(?<!\\.)(?:\\.{4}|\\.{2})(?!\\.)|(?<!,),,(?!,)");

    /** The four command spellings, longest first so the match is unambiguous. */
    private static final String[] COMMAND_SPELLINGS =
            {"https://ddot.it/", "http://ddot.it/", "ddot.it/", "!!"};

    private static final List<String> BUILT_IN_COMMANDS = List.of("on", "off", "block", "this");

    /**
     * Position of an element in the spec-ordered candidate list. The ranking
     * spec's order MUST be total and deterministic; handing the platform only a
     * coarse priority lets its statistics/start-match weighers reshuffle ties,
     * so instead every element carries its final list position and a dedicated
     * sorter pins it.
     */
    private static final Key<Integer> SPEC_ORDER = Key.create("ddot.completion.specOrder");

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

        // Document state at the start of the caret line: block states seed the
        // automaton; inert regions suppress ALL completion, automatic or explicit.
        String precedingText = doc.getCharsSequence().subSequence(0, lineStart).toString();
        DdotContext.DocState docState = DdotContext.documentStateAt(precedingText);
        if (docState == DdotContext.DocState.EXCLUDED || docState == DdotContext.DocState.VERBATIM) {
            result.stopHere();
            return;
        }

        DdotContext.State state = DdotContext.stateAtCursor(beforeCursor, docState);
        DdotRole role = DdotContext.roleOf(state);

        // Where the entity-in-progress starts (after last separator, skipping whitespace).
        int entityStartCol = entityStartColumn(beforeCursor);
        String prefix = beforeCursor.substring(entityStartCol);
        int replaceStart = lineStart + entityStartCol;

        Emitter out = new Emitter(result
                .withPrefixMatcher(new PlainPrefixMatcher(prefix, true))
                .withRelevanceSorter(specOrderSorter()));

        // Command completion (ranking key 1: commands above value candidates).
        String spelling = commandSpelling(prefix);
        if (spelling != null && role != null) {
            boolean bareLine = beforeCursor.substring(0, entityStartCol).isBlank();
            addCommandCandidates(out, file, role, spelling, prefix.substring(spelling.length()),
                    bareLine, replaceStart);
        }

        // Separator snippets — only in a completable slot (never in meta text or
        // other role-less positions), and only when the user is mid-typing a
        // separator (current segment ends in a `.` or `,` run).
        if (role != null) {
            Matcher trail = Pattern.compile("[.,]+$").matcher(prefix);
            if (trail.find()) {
                int trailLen = trail.group().length();
                int snippetReplaceStart = replaceStart + (prefix.length() - trailLen);
                boolean inMeta = beforeCursor.contains(",,");
                String[] labels = inMeta ? new String[]{"..", ",,"} : separatorsForRole(role);
                for (String label : labels) {
                    out.add(separatorElement(label, snippetReplaceStart));
                }
            }
        }

        // States before the meta `..` opener: the meta relation slot is empty and
        // unopened, so items carry their own `..name..` skeleton.
        boolean beforeMetaOpener = state == DdotContext.State.META_START
                || state == DdotContext.State.META_NEXT_INLINE
                || state == DdotContext.State.BLOCK_FIRST_LINE
                || state == DdotContext.State.BLOCK_TRIPLE_LINE;

        // After-`..` relation suggestions: cursor right after `..` (not part of a
        // `....`), emit `..rel..` items whose range absorbs the existing `..`.
        Integer afterDotDot = ((role == DdotRole.PREDICATE || role == DdotRole.META_RELATION)
                && prefix.isEmpty() && !beforeMetaOpener)
                ? findPrecedingDotDotColumn(beforeCursor) : null;

        // Context-aware boosting from the project knowledge graph ("fit", ranking
        // key 2): objects already used with the line's relation (and entities of
        // the type it expects), or relations appropriate for the subject's type.
        Set<String> contextNames = Set.of();
        if (role == DdotRole.OBJECT) {
            String predicate = currentPredicate(beforeCursor);
            if (predicate != null) {
                DdotKgraphService kb = DdotKgraphService.getInstance(file.getProject());
                contextNames = new HashSet<>(kb.objectsForRelation(predicate));
                contextNames.addAll(kb.objectsOfRelationRange(predicate));
            }
        } else if (role == DdotRole.PREDICATE) {
            String subject = currentSubject(beforeCursor);
            if (subject != null) {
                contextNames = DdotKgraphService.getInstance(file.getProject()).relationsForSubjectType(subject);
            }
        }

        if (role != null && !beforeMetaOpener && (afterDotDot == null || !prefix.isEmpty())) {
            for (Candidate c : mergedCandidates(file, role, prefix, contextNames)) {
                out.add(entityElement(c.name(), role, replaceStart));
            }
        }

        if (afterDotDot != null) {
            DdotRole pool = role == DdotRole.META_RELATION ? DdotRole.META_RELATION : DdotRole.PREDICATE;
            for (DdotEntityIndex.Ranked r : DdotEntityIndex.ranked(file, pool)) {
                out.add(continuationPredicateElement(r.name(), lineStart + afterDotDot));
            }
        }

        // Meta relation slot before its `..` opener (after `,,`, after `;;`, or on
        // a fresh line inside a `,,` block): offer `..mr..` with the skeleton.
        if (beforeMetaOpener && prefix.isEmpty()) {
            for (DdotEntityIndex.Ranked r : DdotEntityIndex.ranked(file, DdotRole.META_RELATION)) {
                out.add(continuationPredicateElement(r.name(), replaceStart));
            }
        }

        // Fresh line in continuation context: surface `..rel..` predicates.
        if (docState == DdotContext.DocState.NORMAL && prefix.isEmpty() && beforeCursor.isBlank()
                && DdotContext.isContinuationContext(doc, lineNum)) {
            for (DdotEntityIndex.Ranked r : DdotEntityIndex.ranked(file, DdotRole.PREDICATE)) {
                out.add(continuationPredicateElement(r.name(), replaceStart));
            }
        }
    }

    // Deterministic order ----------------------------------------------------

    /**
     * Assigns each element its position in the spec-ordered list. Elements are
     * added in final ranking order; the {@link #specOrderSorter} then sorts by
     * exactly that position, and nothing else.
     */
    private static final class Emitter {
        private final CompletionResultSet results;
        private int next = 0;

        Emitter(CompletionResultSet results) {
            this.results = results;
        }

        void add(LookupElement element) {
            element.putUserData(SPEC_ORDER, next++);
            results.addElement(element);
        }
    }

    /**
     * A sorter with a single weigher: the element's position in the spec order.
     * Replaces the platform's default weighers (statistics, start-match, …),
     * which would otherwise reshuffle candidates the spec ranks by its
     * alphabetical tiebreak — the order MUST be total and deterministic.
     */
    private static CompletionSorter specOrderSorter() {
        return CompletionSorter.emptySorter().weigh(new LookupElementWeigher("ddotSpecOrder") {
            @Override
            public Comparable<Integer> weigh(@NotNull LookupElement element) {
                Integer order = element.getUserData(SPEC_ORDER);
                return order != null ? order : Integer.MAX_VALUE;
            }
        });
    }

    /**
     * Key 2 of the Autocomplete Specification's ranking, "Fit": a candidate the
     * corpus already links to this line outranks every candidate that merely
     * occupies the right slot. Must exceed the whole priority range
     * {@link DdotEntityIndex} produces (recency, pool, locality and frequency
     * combined), or a frequent name from an early pool could outrank a fitting
     * one.
     */
    private static final double CONTEXT_BOOST = 10_000.0;

    private record Candidate(@NotNull String name, double priority) {}

    /**
     * The final value-candidate order: {@link DdotEntityIndex#ranked} with the
     * fit boost folded in, context-only names appended, the already-typed text
     * dropped (case-insensitively — offering it back would change nothing), and
     * ties broken alphabetically so the order is total.
     */
    private static List<Candidate> mergedCandidates(DdotFile file, DdotRole role, String prefix,
                                                    Set<String> contextNames) {
        List<Candidate> out = new ArrayList<>();
        Set<String> emitted = new HashSet<>();
        for (DdotEntityIndex.Ranked r : DdotEntityIndex.ranked(file, role)) {
            if (r.name().equalsIgnoreCase(prefix)) continue;
            double priority = contextNames.contains(r.name())
                    ? r.priority() + CONTEXT_BOOST : r.priority();
            out.add(new Candidate(r.name(), priority));
            emitted.add(r.name());
        }
        // Context names absent from this slot's vocabulary — e.g. range-inferred
        // objects that appear only as subjects elsewhere — are still valid
        // suggestions; add them with the context boost.
        for (String name : contextNames) {
            if (emitted.contains(name) || name.equalsIgnoreCase(prefix)) continue;
            out.add(new Candidate(name, CONTEXT_BOOST));
        }
        out.sort(Comparator.comparingDouble(Candidate::priority).reversed()
                .thenComparing((Candidate c) -> c.name().toLowerCase(Locale.ROOT))
                .thenComparing(Candidate::name));
        return out;
    }

    // Command completion -------------------------------------------------------

    /**
     * The command spelling {@code slotText} starts with, in the user's own
     * casing (so completing preserves the spelling that was started), or
     * {@code null} when the slot is not a command.
     */
    private static @Nullable String commandSpelling(String slotText) {
        for (String s : COMMAND_SPELLINGS) {
            if (slotText.length() >= s.length() && slotText.regionMatches(true, 0, s, 0, s.length())) {
                return slotText.substring(0, s.length());
            }
        }
        return null;
    }

    /**
     * Command candidates: the four built-ins plus every command name used in the
     * corpus, ranked slot-appropriately (spec "Command completion"):
     * <ul>
     *   <li>Subject → {@code this}, {@code block} first; Object/MetaObject →
     *       {@code block} first; Relation/MetaRelation → none;</li>
     *   <li>{@code off}/{@code on} are pre-parse commands, not field values —
     *       last inside any slot, first only on a bare line;</li>
     *   <li>after {@code block}, the {@code ?end=} parameter is offered as a
     *       follow-on; the marker value itself is never completed.</li>
     * </ul>
     */
    private void addCommandCandidates(Emitter out, DdotFile file, DdotRole role, String spelling,
                                      String typed, boolean bareLine, int replaceStart) {
        // Inside the parameter part: `?end=` is the only completable parameter,
        // and its marker value is an arbitrary author-chosen string — complete
        // `?end=` and stop.
        int query = typed.indexOf('?');
        if (query >= 0) {
            String base = typed.substring(0, query);
            String withParam = "block?end=";
            if (base.equalsIgnoreCase("block")
                    && withParam.regionMatches(true, 0, typed, 0, typed.length())
                    && typed.length() < withParam.length()) {
                out.add(commandElement(spelling, withParam, replaceStart));
            }
            return;
        }

        // Follow-on after completing `block` itself.
        if (typed.equalsIgnoreCase("block")) {
            out.add(commandElement(spelling, "block?end=", replaceStart));
        }

        List<String> slotFirst = switch (role) {
            case SUBJECT -> List.of("this", "block");
            case OBJECT, META_OBJECT -> List.of("block");
            default -> List.of();
        };

        // Everything that is neither slot-appropriate nor pre-parse, in the
        // spec's alphabetical tiebreak order.
        TreeSet<String> rest = new TreeSet<>(
                Comparator.comparing((String n) -> n.toLowerCase(Locale.ROOT))
                        .thenComparing(Comparator.naturalOrder()));
        rest.addAll(BUILT_IN_COMMANDS);
        rest.addAll(DdotEntityIndex.commandNames(file.getProject()));
        rest.removeIf(n -> n.equalsIgnoreCase("on") || n.equalsIgnoreCase("off"));

        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        // `off`/`on` rank first only outside a triple — e.g. a line holding
        // nothing but the command prefix; inside any slot they rank last.
        if (bareLine) {
            ordered.add("off");
            ordered.add("on");
        }
        ordered.addAll(slotFirst);
        ordered.addAll(rest);
        if (!bareLine) {
            ordered.add("off");
            ordered.add("on");
        }

        for (String name : ordered) {
            // The text already typed is not offered back (case-insensitive).
            if (name.equalsIgnoreCase(typed)) continue;
            out.add(commandElement(spelling, name, replaceStart));
        }
    }

    private static LookupElement commandElement(String spelling, String name, int replaceStart) {
        String lookup = spelling + name;
        return LookupElementBuilder.create(lookup)
                .withTypeText(name.startsWith("block?") ? "Block end marker parameter" : "Command")
                .withIcon(AllIcons.Nodes.Template)
                .withInsertHandler((ctx, item) -> {
                    Document doc = ctx.getDocument();
                    doc.replaceString(replaceStart, ctx.getTailOffset(), lookup);
                    ctx.getEditor().getCaretModel().moveToOffset(replaceStart + lookup.length());
                    if (name.equals("block")) {
                        // Follow-on completion for the `?end=` parameter.
                        AutoPopupController.getInstance(ctx.getProject())
                                .scheduleAutoPopup(ctx.getEditor());
                    }
                });
    }

    // Line analysis ------------------------------------------------------------

    /**
     * The relation (predicate slot) on the line currently being typed, or
     * {@code null} if there isn't one yet. Used to scope object suggestions to the
     * relation in play.
     */
    private static @Nullable String currentPredicate(String beforeCursor) {
        Matcher m = SEP.matcher(beforeCursor);
        int last = 0;
        int slot = 0;
        String predicate = null;
        while (m.find()) {
            if (slot == 1) predicate = beforeCursor.substring(last, m.start()).trim();
            slot += m.group().length() == 4 ? 2 : 1;
            last = m.end();
        }
        return predicate != null && !predicate.isEmpty() ? predicate : null;
    }

    /** The subject (slot 0) on the line being typed, or {@code null} if none is complete yet. */
    private static @Nullable String currentSubject(String beforeCursor) {
        Matcher m = SEP.matcher(beforeCursor);
        if (!m.find()) return null; // no separator yet — still typing the subject
        String subject = beforeCursor.substring(0, m.start()).trim();
        return subject.isEmpty() ? null : subject;
    }

    private static int entityStartColumn(String beforeCursor) {
        Matcher m = SEP_OR_META.matcher(beforeCursor);
        int lastSepEnd = 0;
        while (m.find()) lastSepEnd = m.end();
        int p = lastSepEnd;
        while (p < beforeCursor.length() && Character.isWhitespace(beforeCursor.charAt(p))) p++;
        return p;
    }

    private static String[] separatorsForRole(@NotNull DdotRole role) {
        return switch (role) {
            case SUBJECT -> new String[]{"..", "...."};
            case PREDICATE -> new String[]{".."};
            case OBJECT -> new String[]{",,"};
            case META_RELATION -> new String[]{".."};
            // `;;` starts the next inline pair; `,,` closes a meta block.
            case META_OBJECT -> new String[]{";;", ",,"};
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

    /**
     * End offset extended past a following isolated {@code ..} (plus surrounding
     * spaces) on the same line. Completing a relation inside the untyped form —
     * {@code a .. .. b} or {@code a ..<caret>.. b} — must produce
     * {@code a ..name.. b}: the inserted text carries its own closing
     * {@code .. }, so the leftover half of the untyped separator is consumed.
     * Also folds the pre-existing closing {@code ..} when a typed relation is
     * re-completed in place.
     */
    private static int consumeFollowingDotDot(Document doc, int end) {
        CharSequence cs = doc.getCharsSequence();
        int i = end;
        while (i < cs.length() && (cs.charAt(i) == ' ' || cs.charAt(i) == '\t')) i++;
        if (i + 2 <= cs.length() && cs.charAt(i) == '.' && cs.charAt(i + 1) == '.'
                && (i + 2 == cs.length() || cs.charAt(i + 2) != '.')) {
            i += 2;
            while (i < cs.length() && (cs.charAt(i) == ' ' || cs.charAt(i) == '\t')) i++;
            return i;
        }
        return end;
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
            case META_RELATION -> "Meta relation";
            case META_OBJECT -> "Meta object";
            case META -> "Metadata";
        };
        return LookupElementBuilder.create(entity)
                .withTypeText(roleLabel + " in project")
                .withIcon(AllIcons.Nodes.Variable)
                .withInsertHandler((ctx, item) -> {
                    Document doc = ctx.getDocument();
                    boolean relationLike = role == DdotRole.PREDICATE || role == DdotRole.META_RELATION;
                    String inserted = switch (role) {
                        case SUBJECT -> entity + " ..";
                        // Closing the relation advances to its object.
                        case PREDICATE, META_RELATION -> entity + ".. ";
                        case OBJECT -> entity + "\n";
                        case META_OBJECT, META -> entity;
                    };
                    // Untyped-form rewrite: the inserted `name.. ` replaces any
                    // `..` already waiting after the caret, so `a .. .. b`
                    // becomes `a ..name.. b`, never `a ..name.. .. b`.
                    int end = relationLike
                            ? consumeFollowingDotDot(doc, ctx.getTailOffset())
                            : ctx.getTailOffset();
                    doc.replaceString(replaceStart, end, inserted);
                    ctx.getEditor().getCaretModel().moveToOffset(replaceStart + inserted.length());
                    if (relationLike) {
                        // Recency (ranking key 3) applies to both relation-like
                        // slots, p and mr.
                        DdotRecentRelations.getInstance(ctx.getProject()).recordUse(entity);
                    }
                    if (role == DdotRole.SUBJECT || relationLike) {
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
                    int end = consumeFollowingDotDot(doc, ctx.getTailOffset());
                    doc.replaceString(replaceStart, end, inserted);
                    ctx.getEditor().getCaretModel().moveToOffset(replaceStart + inserted.length());
                    DdotRecentRelations.getInstance(ctx.getProject()).recordUse(pred);
                    AutoPopupController.getInstance(ctx.getProject())
                            .scheduleAutoPopup(ctx.getEditor());
                });
    }

}
