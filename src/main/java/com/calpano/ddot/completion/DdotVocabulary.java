package com.calpano.ddot.completion;

import com.calpano.ddot.index.DdotEntityIndex;
import com.calpano.kbase.KIds;
import com.calpano.kbase.KProperty;
import com.calpano.kbase.KTriples;
import com.calpano.kbase.inmem.Kedge;
import com.calpano.kbase.inmem.Kgraph;
import com.calpano.kbase.text.SortedTextIndex;
import com.calpano.kbase.text.TextHit;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The names completion offers, read from the project's knowledge graph: one pool per position a name is
 * used in, each with how often it was stated, and a {@link SortedTextIndex} per pool for prefix lookups.
 *
 * <p>Counts are statements: a link stated in three places counts three times for its subject, relation and
 * object. Meta comes from the link {@linkplain Kedge#properties() properties}: a key counts once per place
 * its property was stated, and so does a value.
 *
 * <p>A snapshot: {@link DdotKgraphService} builds a new one after the graph changed. Pure (no IDE
 * platform), so it is unit-testable on a hand-built graph.
 */
public final class DdotVocabulary {

    /** Where a name was used. */
    public enum Pool { SUBJECT, RELATION, OBJECT, META_RELATION, META_OBJECT }

    public static final DdotVocabulary EMPTY = new DdotVocabulary(new EnumMap<>(Pool.class));

    private final Map<Pool, Map<String, Integer>> counts;
    private final Map<Pool, SortedTextIndex<String>> texts = new EnumMap<>(Pool.class);

    private DdotVocabulary(Map<Pool, Map<String, Integer>> counts) {
        this.counts = counts;
        counts.forEach((pool, names) -> {
            List<TextHit<String>> hits = new ArrayList<>(names.size());
            for (String name : names.keySet()) hits.add(new TextHit<>(name, name));
            SortedTextIndex<String> index = new SortedTextIndex<>();
            index.addAll(hits);
            texts.put(pool, index);
        });
    }

    /**
     * The vocabulary of {@code graph}. {@code sourceUris} are the files it was read from: a {@code !!this}
     * stands for its own file, and the graph holds that file's URI in its place, which is no name to offer.
     */
    public static @NotNull DdotVocabulary of(@NotNull Kgraph graph, @NotNull Set<String> sourceUris) {
        Map<Pool, Map<String, Integer>> counts = new EnumMap<>(Pool.class);
        graph.read(() -> {
            graph.edges().forEach(link -> {
                int statements = Math.max(1, link.sourceLocations().size());
                for (KTriples.Spo t : KTriples.of(link)) {
                    count(counts, Pool.SUBJECT, KIds.toText(t.s()), statements, sourceUris);
                    // `links to` is what `....` means; it is written as a separator, never as a name.
                    if (!t.p().equals(KTriples.LINKS_TO_ID)) {
                        count(counts, Pool.RELATION, KIds.toText(t.p()), statements, sourceUris);
                    }
                    count(counts, Pool.OBJECT, KIds.toText(t.o()), statements, sourceUris);
                }
                for (KProperty property : link.properties()) {
                    int stated = Math.max(1, link.sourceLocationsOf(property).size());
                    count(counts, Pool.META_RELATION, property.key(), stated, sourceUris);
                    count(counts, Pool.META_OBJECT, property.value(), stated, sourceUris);
                }
            });
            return null;
        });
        return new DdotVocabulary(counts);
    }

    private static void count(Map<Pool, Map<String, Integer>> counts, Pool pool, String name, int n,
                              Set<String> sourceUris) {
        if (!isName(name, sourceUris)) return;
        counts.computeIfAbsent(pool, k -> new HashMap<>()).merge(name, n, Integer::sum);
    }

    /**
     * Whether {@code text} is a name to offer: not empty, not a command such as {@code ddot.it/block}, not a
     * {@code ::}-qualified name, and not a resolved {@code !!this}.
     */
    static boolean isName(String text, Set<String> sourceUris) {
        return !text.isEmpty() && !text.contains("::") && DdotEntityIndex.commandNameOf(text) == null
                && !sourceUris.contains(text);
    }

    /** Every name of {@code pool} with its statement count. */
    public @NotNull Map<String, Integer> counts(@NotNull Pool pool) {
        return counts.getOrDefault(pool, Map.of());
    }

    /**
     * The names of {@code pool} starting with {@code prefix}, ignoring case, with their statement counts, in
     * the order of their folded text. The empty prefix gives every name.
     */
    public @NotNull Map<String, Integer> withPrefix(@NotNull Pool pool, @NotNull String prefix) {
        SortedTextIndex<String> index = texts.get(pool);
        if (index == null) return Map.of();
        Map<String, Integer> all = counts(pool);
        Map<String, Integer> out = new LinkedHashMap<>();
        for (TextHit<String> hit : index.prefix(prefix)) out.put(hit.item(), all.getOrDefault(hit.item(), 0));
        return out;
    }
}
