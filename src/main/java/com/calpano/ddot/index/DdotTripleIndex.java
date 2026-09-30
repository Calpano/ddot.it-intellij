package com.calpano.ddot.index;

import it.ddot.core.event.DdotEvent;
import it.ddot.core.io.DdotEventExporter;
import it.ddot.core.event.DdotTriple;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.indexing.DataIndexer;
import com.intellij.util.indexing.FileBasedIndex;
import com.intellij.util.indexing.FileBasedIndexExtension;
import com.intellij.util.indexing.FileContent;
import com.intellij.util.indexing.ID;
import com.intellij.util.io.DataExternalizer;
import com.intellij.util.io.DataInputOutputUtil;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.IOUtil;
import com.intellij.util.io.KeyDescriptor;
import it.ddot.core.io.DdotParser;
import it.ddot.core.source.ISnippet;
import it.ddot.core.source.ISourceLocation;
import it.ddot.core.source.ISourceResourceAddress;
import org.jetbrains.annotations.NotNull;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Per-file full triples ({@code from, type, to}), the persistence foundation for
 * the graph-aware query layer ({@link com.calpano.ddot.completion.DdotKgraphService}).
 * Unlike the flat role/pair indexes this preserves the S-P-O linkage a knowledge
 * graph needs. Extraction reuses the canonical {@link DdotEventExporter}; the
 * cross-file assembly into a {@code Kgraph} happens at query time.
 */
public final class DdotTripleIndex extends FileBasedIndexExtension<String, List<DdotTripleIndex.Located>> {

    /** A triple plus the 1-based line of the host file it is stated on. */
    public record Located(@NotNull DdotTriple triple, int line) {}

    public static final ID<String, List<Located>> NAME = ID.create("com.calpano.ddot.triples");

    // Single key — each file contributes its triple list; the graph reads them all.
    private static final String KEY = "T";
    // 2: DdotEventExporter now yields `!!block` bodies, meta text, `;;`-split
    //    inline meta pairs and untyped meta — so the extracted triples changed.
    // 3: host-level `!!off`/`!!on` (written outside the fenced chunks) now
    //    exclude the chunks they cover, and the off state threads across chunks.
    // 4: an untyped link (`....` / `.. ..`) now carries DdotVocabulary.LINKS_TO
    //    as its relation instead of a null predicate, so the stored type of
    //    every untyped triple changed from "" to "links to".
    // 5: each triple carries the host-file line it is stated on.
    private static final int VERSION = 5;
    private static final String EVENT_KIND = "ddot";

    @Override
    public @NotNull ID<String, List<Located>> getName() {
        return NAME;
    }

    @Override
    public @NotNull DataIndexer<String, List<Located>, FileContent> getIndexer() {
        return inputData -> {
            List<Located> triples = extract(inputData.getFile().getExtension(),
                    inputData.getContentAsText(), inputData.getFileName());
            return triples.isEmpty() ? Map.of() : Map.of(KEY, triples);
        };
    }

    /** All triples in {@code text}, across every embedded ddot chunk, with host lines. Pure — unit-testable. */
    public static @NotNull List<Located> extract(String extension, @NotNull CharSequence text, String source) {
        List<Located> out = new ArrayList<>();
        ISnippet snippet = ISnippet.of(EVENT_KIND, ISourceLocation.of(ISourceResourceAddress.of(source), 1));
        for (DdotEmbeds.Chunk chunk : DdotEmbeds.extractChunks(extension, text)) {
            for (DdotEvent e : DdotParser.parse(chunk.text(), snippet, chunk.startsOff(), chunk.firstLine())) {
                if (e.from.contains("::") || e.to.contains("::")) continue;
                out.add(new Located(new DdotTriple(e.from, e.type, e.to), e.startLineInResource()));
            }
        }
        return out;
    }

    @Override
    public @NotNull KeyDescriptor<String> getKeyDescriptor() {
        return EnumeratorStringDescriptor.INSTANCE;
    }

    @Override
    public @NotNull DataExternalizer<List<Located>> getValueExternalizer() {
        return new DataExternalizer<>() {
            @Override
            public void save(@NotNull DataOutput out, List<Located> value) throws IOException {
                DataInputOutputUtil.writeINT(out, value.size());
                for (Located l : value) {
                    IOUtil.writeUTF(out, l.triple().from());
                    IOUtil.writeUTF(out, l.triple().type());
                    IOUtil.writeUTF(out, l.triple().to());
                    DataInputOutputUtil.writeINT(out, l.line());
                }
            }

            @Override
            public List<Located> read(@NotNull DataInput in) throws IOException {
                int n = DataInputOutputUtil.readINT(in);
                List<Located> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    String from = IOUtil.readUTF(in);
                    String type = IOUtil.readUTF(in);
                    String to = IOUtil.readUTF(in);
                    int line = DataInputOutputUtil.readINT(in);
                    list.add(new Located(new DdotTriple(from, type, to), line));
                }
                return list;
            }
        };
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

    /** Feed each indexed file's triples (with its {@link VirtualFile}) to {@code consumer}, project-wide. */
    public static void forEachFile(@NotNull Project project,
                                   @NotNull BiConsumer<VirtualFile, List<Located>> consumer) {
        FileBasedIndex.getInstance().processValues(NAME, KEY, null,
                (file, value) -> { consumer.accept(file, value); return true; },
                GlobalSearchScope.projectScope(project));
    }

    /** The triples a single file contributed, from the index (empty if none/not indexed). */
    public static @NotNull List<Located> fileTriples(@NotNull Project project, @NotNull VirtualFile file) {
        List<Located> triples = FileBasedIndex.getInstance().getFileData(NAME, file, project).get(KEY);
        return triples != null ? triples : List.of();
    }
}
