package com.calpano.ddot.index;

import com.calpano.ddot.completion.DdotRecentRelations;
import it.ddot.core.token.DdotTokenizer;
import it.ddot.core.token.Token;
import com.calpano.ddot.psi.DdotFile;
import com.calpano.ddot.psi.DdotPsiUtil;
import it.ddot.core.DdotSlot;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.indexing.DataIndexer;
import com.intellij.util.indexing.FileBasedIndex;
import com.intellij.util.indexing.FileBasedIndexExtension;
import com.intellij.util.indexing.FileContent;
import com.intellij.util.indexing.ID;
import com.intellij.util.io.DataExternalizer;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import it.ddot.core.token.TokenName;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persisted, project-wide vocabulary index backing ddot.it completion. Maps a
 * role tag ({@code S}/{@code P}/{@code O}) to the set of subject / predicate /
 * object names found in each indexed file, extracted by {@link DdotEmbeds} from
 * {@code .ddot}, Markdown ({@code ```ddot}) and AsciiDoc ({@code [source,ddot]})
 * files and classified by the canonical {@link DdotTokenizer}.
 *
 * <p>{@code FileBasedIndex} keeps this incremental and on disk, so completion in
 * one file sees relations/entities authored anywhere in the project without
 * re-reading files. Keys are intentionally few (three role tags) because the hot
 * query is "all names of role R" — answered with a single {@code processValues}
 * per role rather than a scan over a per-name key space.
 */
public final class DdotEntityIndex extends FileBasedIndexExtension<String, Map<String, Integer>> {

    public static final ID<String, Map<String, Integer>> NAME = ID.create("com.calpano.ddot.entityVocab");

    private static final String SUBJECTS = "S";
    private static final String PREDICATES = "P";
    private static final String OBJECTS = "O";
    private static final String META_RELATIONS = "MR";
    private static final String META_OBJECTS = "MO";
    /** Command NAMES (`this`, `off`, …) — never offered as s/p/o vocabulary. */
    private static final String COMMANDS = "C";

    // Bump when extraction/classification changes so the IDE rebuilds the index.
    // 2: added the MR/MO meta pools.
    // 3: values carry occurrence COUNTS, for frequency ranking.
    // 4: host-level `!!off`/`!!on` now exclude chunks; added the C command pool.
    private static final int VERSION = 4;

    @Override
    public @NotNull ID<String, Map<String, Integer>> getName() {
        return NAME;
    }

    @Override
    public @NotNull DataIndexer<String, Map<String, Integer>, FileContent> getIndexer() {
        return inputData -> vocabulary(inputData.getFile().getExtension(), inputData.getContentAsText());
    }

    /**
     * The per-file index value: role tag → names, extracted from every ddot chunk
     * in {@code text} and classified by {@link DdotTokenizer}. Pure (no PSY/VFS) so
     * it is unit-testable in isolation.
     */
    static @NotNull Map<String, Map<String, Integer>> vocabulary(@Nullable String extension,
                                                                 @NotNull CharSequence text) {
        List<DdotEmbeds.Chunk> chunks = DdotEmbeds.extractChunks(extension, text);
        if (chunks.isEmpty()) return Map.of();

        Map<String, Map<String, Integer>> pools = new HashMap<>(8);
        for (DdotEmbeds.Chunk chunk : chunks) {
            // A chunk that begins inside a host-level `!!off` region is parsed
            // with that state: its excluded lines contribute no vocabulary.
            for (Token t : DdotTokenizer.tokenize(chunk.text(), chunk.startsOff())) {
                if (t.tokenName() == TokenName.COMMAND) {
                    // Command names go to their own pool — kept OUT of the
                    // s/p/o vocabulary (a command is not an entity name), but
                    // harvested for command completion.
                    String cmd = commandNameOf(t.text());
                    if (cmd != null) {
                        pools.computeIfAbsent(COMMANDS, k -> new HashMap<>()).merge(cmd, 1, Integer::sum);
                    }
                    continue;
                }
                String name = t.text();
                if (name.isEmpty() || name.contains("::")) continue;
                String pool = switch (t.tokenName()) {
                    case TokenName.SUBJECT -> SUBJECTS;
                    case TokenName.RELATION ->  PREDICATES;
                    case TokenName.OBJECT -> OBJECTS;
                    // Meta names get their OWN pools. Folding them into P/O would
                    // still answer most queries, but a name used only in a meta
                    // position — the common case for annotation vocabulary like
                    // `since` or `confidence` — would never be indexed at all,
                    // and the PSI layer cannot supply it either: its role model
                    // has one coarse META, so the in-file tier is always empty
                    // for these two slots.
                    case TokenName.META_RELATION -> META_RELATIONS;
                    case TokenName.META_OBJECT -> META_OBJECTS;
                    // Free meta text is prose, not vocabulary; operators,
                    // commands and excluded regions are not names.
                    default -> null;
                };
                if (pool == null) continue;
                // EVERY occurrence counts, including repeats within one file —
                // that is what makes frequency ranking meaningful.
                pools.computeIfAbsent(pool, k -> new HashMap<>()).merge(name, 1, Integer::sum);
            }
        }
        return pools;
    }

    /**
     * The NAME of a command-shaped token: the part after `!!` / `ddot.it/` /
     * `http(s)://ddot.it/`, without any `?query`/`#fragment`. {@code null} when
     * {@code text} is not command-shaped or the name is empty.
     */
    static @Nullable String commandNameOf(@NotNull String text) {
        String rest;
        if (text.startsWith("!!")) rest = text.substring(2);
        else {
            int host = text.indexOf("ddot.it/");
            if (host < 0) return null;
            rest = text.substring(host + "ddot.it/".length());
        }
        int q = rest.indexOf('?');
        int h = rest.indexOf('#');
        int cut = q < 0 ? h : (h < 0 ? q : Math.min(q, h));
        if (cut >= 0) rest = rest.substring(0, cut);
        return rest.isEmpty() ? null : rest;
    }

    @Override
    public @NotNull KeyDescriptor<String> getKeyDescriptor() {
        return EnumeratorStringDescriptor.INSTANCE;
    }

    @Override
    public @NotNull DataExternalizer<Map<String, Integer>> getValueExternalizer() {
        return StringCountExternalizer.INSTANCE;
    }

    @Override
    public int getVersion() {
        return VERSION;
    }

    @Override
    public FileBasedIndex.@NotNull InputFilter getInputFilter() {
        return file -> DdotEmbeds.isSupportedExtension(file.getExtension());
    }

    @Override
    public boolean dependsOnFileContent() {
        return true;
    }

    // Query side ------------------------------------------------------------

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
     * in {@link com.calpano.ddot.completion.DdotKgraphService} boosts
     * type-appropriate names rather than suppressing the rest.
     */
    private static @NotNull List<String> poolsFor(@NotNull DdotSlot role) {
        return switch (role) {
            case SUBJECT -> List.of(SUBJECTS, OBJECTS, META_OBJECTS, PREDICATES, META_RELATIONS);
            case RELATION -> List.of(PREDICATES, META_RELATIONS);
            case OBJECT -> List.of(OBJECTS, SUBJECTS, META_OBJECTS, PREDICATES, META_RELATIONS);
            case META_RELATION -> List.of(META_RELATIONS, PREDICATES);
            case META_OBJECT -> List.of(META_OBJECTS, OBJECTS, SUBJECTS, META_RELATIONS, PREDICATES);
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
        Set<String> out = new HashSet<>();
        for (String pool : poolsFor(role)) out.addAll(poolCounts(project, pool).keySet());
        return out;
    }

    /**
     * Distinct command NAMES (the part after `!!` / `ddot.it/` /
     * `http(s)://ddot.it/`, without `?query`/`#fragment`) harvested from every
     * command token in every indexed file, sorted case-insensitively (then by
     * code point, so the order is total). Empty in dumb mode.
     */
    public static @NotNull List<String> commandNames(@NotNull Project project) {
        List<String> out = new ArrayList<>(poolCounts(project, COMMANDS).keySet());
        out.sort(Comparator.comparing((String s) -> s.toLowerCase(Locale.ROOT))
                .thenComparing(Comparator.naturalOrder()));
        return out;
    }

    /** Name → total occurrences of that name in {@code pool}, summed over every indexed file. */
    private static @NotNull Map<String, Integer> poolCounts(@NotNull Project project, @NotNull String pool) {
        if (DumbService.isDumb(project)) return Map.of();
        Map<String, Integer> out = new HashMap<>();
        try {
            FileBasedIndex.getInstance().processValues(NAME, pool, null,
                    (file, value) -> {
                        value.forEach((name, count) -> out.merge(name, count, Integer::sum));
                        return true;
                    },
                    GlobalSearchScope.projectScope(project));
        } catch (IndexNotReadyException ignored) {
            return Map.of();
        }
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
        // namesByRole keeps duplicates, so it doubles as the live occurrence
        // count for this file — the index lags unsaved edits.
        Map<String, Integer> inFileCounts = new HashMap<>();
        for (String name : DdotPsiUtil.namesByRole(file, role)) inFileCounts.merge(name, 1, Integer::sum);
        Set<String> inFile = inFileCounts.keySet();

        Set<String> sameFile = namesInFile(file);
        sameFile.addAll(inFile);

        // Each name is credited to the FIRST catalog pool that holds it.
        List<String> pools = poolsFor(role);
        Map<String, Integer> poolOf = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        LinkedHashSet<String> inProject = new LinkedHashSet<>();
        for (int i = 0; i < pools.size(); i++) {
            for (Map.Entry<String, Integer> e : poolCounts(file.getProject(), pools.get(i)).entrySet()) {
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
                // Corpus count where the index knows the name; otherwise the
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
