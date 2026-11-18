package com.calpano.ddot.completion;

import com.calpano.ddot.completion.DdotVocabulary.Pool;
import com.calpano.ddot.psi.DdotFile;
import com.calpano.ddot.psi.DdotPsiUtil;
import com.intellij.openapi.project.Project;
import it.ddot.core.DdotSlot;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Completion candidates and their rank. The project's names come from the knowledge graph
 * ({@link DdotKgraphService#vocabulary()}), looked up by prefix in its text index; the current file's
 * names come from the PSI, so a name typed a second ago counts.
 */
public final class DdotCandidates {

    private DdotCandidates() {}

    /**
     * The pools a slot draws on, in the Autocomplete Specification's query
     * catalog order. The two families are value-like ({@code s}, {@code o},
     * {@code mo}) and relation-like ({@code p}, {@code mr}); each query lists its
     * own slot, then the rest of its family, then the other family.
     *
     * <p>A relation-like cursor stays inside its own family: completing a
     * relation from the set of <em>objects</em> would offer values where a
     * relation name belongs, so the value-like pools are not offered there at
     * all.
     *
     * <p>Breadth here is deliberate and is not the same question as relevance.
     * Completion is permissive — an entity named as a subject elsewhere is a
     * perfectly good object — and <em>ranking</em>, not filtering, is what puts
     * the likely candidates on top. That is why the type-aware range inference
     * in {@link DdotKgraphService} boosts
     * type-appropriate names rather than suppressing the rest.
     */
    private static @NotNull List<Pool> poolsFor(@NotNull DdotSlot role) {
        return switch (role) {
            case SUBJECT -> List.of(Pool.SUBJECT, Pool.OBJECT, Pool.META_OBJECT, Pool.RELATION, Pool.META_RELATION);
            case RELATION -> List.of(Pool.RELATION, Pool.META_RELATION);
            case OBJECT -> List.of(Pool.OBJECT, Pool.SUBJECT, Pool.META_OBJECT, Pool.RELATION, Pool.META_RELATION);
            case META_RELATION -> List.of(Pool.META_RELATION, Pool.RELATION);
            case META_OBJECT -> List.of(Pool.META_OBJECT, Pool.OBJECT, Pool.SUBJECT, Pool.META_RELATION, Pool.RELATION);
            case META_START -> List.of();
            case META_NEXT -> List.of();
        };
    }

    /**
     * All names of {@code role} authored anywhere in {@code project}. Returns an
     * empty set in dumb mode (indexes unavailable) — callers fall back to the
     * current file's live names.
     */
    public static @NotNull Set<String> projectNames(@NotNull Project project, @NotNull DdotSlot role) {
        DdotVocabulary vocabulary = DdotKgraphService.getInstance(project).vocabulary();
        Set<String> out = new HashSet<>();
        for (Pool pool : poolsFor(role)) out.addAll(vocabulary.counts(pool).keySet());
        return out;
    }

    // Ranking -------------------------------------------------------------

    /** Number of recently-used relations pinned to the top of the list. */
    public static final int RECENT_SLOTS = 3;

    /**
     * The priority is a positional numeral: each ranking key gets a band wider
     * than the sum of every band below it, so a higher key can never be
     * outvoted by lower ones. Keys, highest first (Autocomplete Spec, "Ranking"):
     * <pre>
     *   2 Fit         CONTEXT_BOOST, added by DdotCompletionContributor   10000
     *   3 Recency     PRIORITY_RECENT_BASE + slot                    1301..1303
     *   4 Query slot  (pools - index) * PRIORITY_POOL_STEP              200..1000
     *   5 Locality    PRIORITY_IN_FILE                                     0|100
     *   6 Frequency   frequencyBonus()                                    0..&lt;100
     *   7 Name        alphabetical, applied by the comparator below
     * </pre>
     * Get the widths wrong and the order silently violates the spec — a
     * high-frequency candidate from a late pool would jump an early-pool one.
     */
    private static final double PRIORITY_IN_PROJECT = 0.0;
    private static final double PRIORITY_IN_FILE = 100.0;
    private static final double PRIORITY_POOL_STEP = 200.0;
    private static final double PRIORITY_RECENT_BASE = 1300.0;

    /** Frequency saturation constant: the count at which the bonus reaches half its ceiling. */
    private static final double FREQUENCY_HALF = 10.0;
    private static final double FREQUENCY_CEILING = 99.0;

    /**
     * Frequency contribution, strictly increasing in {@code count} but bounded
     * below {@link #PRIORITY_IN_FILE} so it can only order candidates that tie
     * on every higher key. Saturating rather than linear: the difference between
     * 1 and 5 uses matters, between 500 and 504 it does not.
     */
    private static double frequencyBonus(int count) {
        if (count <= 0) return 0.0;
        return FREQUENCY_CEILING * count / (count + FREQUENCY_HALF);
    }

    /** A completion candidate with its sort priority (higher sorts first). */
    public record Ranked(@NotNull String name, double priority) {}

    /**
     * Roles scanned for locality. {@code META_START} is the PSI's coarse meta
     * role and stands in for both {@code META_RELATION} and {@code META_OBJECT}.
     */
    private static final List<DdotSlot> LOCALITY_ROLES =
            List.of(DdotSlot.SUBJECT, DdotSlot.RELATION, DdotSlot.OBJECT, DdotSlot.META_START);

    /**
     * Every name used anywhere in {@code file}, in any slot.
     *
     * <p>Read from the PSI, not the index: this is the live picture, so a name
     * typed a second ago counts even though the index hasn't reindexed the
     * unsaved document yet.
     *
     * <p>Two callers, and they must agree. {@link #ranked} spends it as locality
     * credit (ranking key 5) — a name used ANYWHERE in this document outranks a
     * project-only one in every slot whose catalog admits it, not just in the
     * slot it was used in. The completion contributor spends it on the lookup's
     * type text, so "Object in file" marks exactly the candidates that got that
     * credit. Ranking and label would drift apart if each derived its own set.
     */
    public static @NotNull Set<String> namesInFile(@NotNull DdotFile file) {
        Set<String> out = new HashSet<>();
        for (DdotSlot r : LOCALITY_ROLES) out.addAll(DdotPsiUtil.namesByRole(file, r));
        return out;
    }

    /**
     * Project-wide completion candidates for {@code role}, ranked as the
     * Autocomplete Specification prescribes — by <em>query slot</em> first
     * (the {@link #poolsFor} catalog order), then same-file before other-file —
     * with two additions on top:
     * <ol>
     *   <li>up to {@link #RECENT_SLOTS} most recently used relations (relations only),
     *       most-recent first;</li>
     *   <li>the caller's context boost for type-appropriate names, which outranks
     *       pool order entirely.</li>
     * </ol>
     * Each name appears once, at its highest applicable tier.
     */
    public static @NotNull List<Ranked> ranked(@NotNull DdotFile file, @NotNull DdotSlot role) {
        return ranked(file, role, "");
    }

    /**
     * {@link #ranked(DdotFile, DdotSlot)} for the project names starting with {@code prefix} (ignoring case),
     * looked up in the vocabulary's text index rather than filtered afterwards. In-file and recent names are
     * kept whatever their prefix: the completion's prefix matcher has the last word on those.
     */
    public static @NotNull List<Ranked> ranked(@NotNull DdotFile file, @NotNull DdotSlot role, @NotNull String prefix) {
        // namesByRole keeps duplicates, so it doubles as the live occurrence
        // count for this file — the graph has no incomplete triples.
        Map<String, Integer> inFileCounts = new HashMap<>();
        for (String name : DdotPsiUtil.namesByRole(file, role)) inFileCounts.merge(name, 1, Integer::sum);
        Set<String> inFile = inFileCounts.keySet();

        Set<String> sameFile = namesInFile(file);
        sameFile.addAll(inFile);

        // Each name is credited to the FIRST catalog pool that holds it.
        List<Pool> pools = poolsFor(role);
        DdotVocabulary vocabulary = DdotKgraphService.getInstance(file.getProject()).vocabulary();
        Map<String, Integer> poolOf = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        LinkedHashSet<String> inProject = new LinkedHashSet<>();
        for (int i = 0; i < pools.size(); i++) {
            for (Map.Entry<String, Integer> e : vocabulary.withPrefix(pools.get(i), prefix).entrySet()) {
                if (poolOf.putIfAbsent(e.getKey(), i) == null) inProject.add(e.getKey());
                counts.merge(e.getKey(), e.getValue(), Integer::sum);
            }
        }

        // Recency (ranking key 3) applies to the relation-LIKE slots — the
        // inline relation and the meta relation alike.
        boolean relationLike = role == DdotSlot.RELATION || role == DdotSlot.META_RELATION;
        List<String> recent = relationLike
                ? DdotRecentRelations.getInstance(file.getProject()).recent(RECENT_SLOTS)
                : List.of();
        Map<String, Integer> recentRank = new HashMap<>();
        for (int i = 0; i < recent.size(); i++) recentRank.putIfAbsent(recent.get(i), i);

        // Surface recents even if absent from the current vocabulary; then in-file, then project.
        LinkedHashSet<String> all = new LinkedHashSet<>(recent);
        all.addAll(inFile);
        all.addAll(inProject);

        List<Ranked> out = new ArrayList<>(all.size());
        for (String name : all) {
            Integer ri = recentRank.get(name);
            double priority;
            if (ri != null) {
                priority = PRIORITY_RECENT_BASE + (RECENT_SLOTS - ri);
            } else {
                // A name the PSI reports for this exact role belongs to the
                // slot's own pool by definition — the index simply may not have
                // caught up with an unsaved edit yet. Otherwise the pool credit
                // is the first ADMITTING pool per the catalog, regardless of
                // where in this file the name also appears.
                int pool = inFile.contains(name) ? 0 : poolOf.getOrDefault(name, pools.size());
                // Locality credit for any same-file use, whatever its role.
                boolean here = sameFile.contains(name);
                // Corpus count where the graph knows the name; otherwise the
                // live in-file count, which is all an unsaved edit has.
                int count = counts.getOrDefault(name, inFileCounts.getOrDefault(name, 0));
                priority = (pools.size() - pool) * PRIORITY_POOL_STEP
                        + (here ? PRIORITY_IN_FILE : PRIORITY_IN_PROJECT)
                        + frequencyBonus(count);
            }
            out.add(new Ranked(name, priority));
        }
        // Key 7 of the ranking spec: ties break alphabetically, so the same
        // corpus and prefix always produce the same list. Without it the order
        // within a tier came from HashSet iteration — stable in practice, but
        // not specified, and authors build muscle memory from list positions.
        out.sort(Comparator.comparingDouble(Ranked::priority).reversed()
                .thenComparing(r -> r.name().toLowerCase(Locale.ROOT))
                .thenComparing(Ranked::name));
        return out;
    }
}
