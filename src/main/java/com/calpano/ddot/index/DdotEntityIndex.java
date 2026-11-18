package com.calpano.ddot.index;

import it.ddot.core.embed.DdotEmbeds;
import it.ddot.core.token.DdotTokenizer;
import it.ddot.core.token.Token;
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
import java.util.Locale;
import java.util.List;
import java.util.Map;

/**
 * Persisted, project-wide index of the ddot.it <em>command names</em> ({@code this}, {@code block}, …)
 * used in {@code .ddot}, Markdown ({@code ```ddot}) and AsciiDoc ({@code [source,ddot]}) files, found by
 * the canonical {@link DdotTokenizer}. Command completion offers them.
 *
 * <p>Commands are not knowledge, so the graph does not have them. Every other name completion offers —
 * subjects, relations, objects, meta — comes from the knowledge graph instead, see
 * {@link com.calpano.ddot.completion.DdotVocabulary}.
 */
public final class DdotEntityIndex extends FileBasedIndexExtension<String, Map<String, Integer>> {

    public static final ID<String, Map<String, Integer>> NAME = ID.create("com.calpano.ddot.entityVocab");

    /** Command NAMES (`this`, `off`, …) — never offered as s/p/o vocabulary. */
    private static final String COMMANDS = "C";

    // Bump when extraction/classification changes so the IDE rebuilds the index.
    // 2: added the MR/MO meta pools.
    // 3: values carry occurrence COUNTS, for frequency ranking.
    // 4: host-level `!!off`/`!!on` now exclude chunks; added the C command pool.
    // 5: only the C pool; names for completion come from the knowledge graph (DdotVocabulary).
    private static final int VERSION = 5;

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

        Map<String, Integer> commands = new HashMap<>();
        for (DdotEmbeds.Chunk chunk : chunks) {
            // A chunk that begins inside a host-level `!!off` region is parsed with that state.
            for (Token t : DdotTokenizer.tokenize(chunk.text(), chunk.startsOff())) {
                if (t.tokenName() != TokenName.COMMAND) continue;
                String cmd = commandNameOf(t.text());
                if (cmd != null) commands.merge(cmd, 1, Integer::sum);
            }
        }
        return commands.isEmpty() ? Map.of() : Map.of(COMMANDS, commands);
    }

    /**
     * The NAME of a command-shaped token: the part after `!!` / `ddot.it/` /
     * `http(s)://ddot.it/`, without any `?query`/`#fragment`. {@code null} when
     * {@code text} is not command-shaped or the name is empty.
     */
    public static @Nullable String commandNameOf(@NotNull String text) {
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

}
