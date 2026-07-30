package com.calpano.ddot.completion;

import com.calpano.ddot.index.DdotEmbeds;
import com.calpano.ddot.it.event.DdotTriple;
import com.calpano.ddot.index.DdotTripleIndex;
import com.calpano.ddot.it.kserver.base.Kendpoint;
import com.calpano.ddot.it.kserver.impl.mem.Kedge;
import com.calpano.ddot.it.kserver.impl.mem.Kgraph;
import com.calpano.ddot.it.source.ISourceLocation;
import com.calpano.ddot.it.source.ISourceResourceAddress;
import com.calpano.ddot.it.kserver.triple.TripleEventFromLine;
import com.calpano.ddot.it.source.handler.TripleEvent2Kgraph;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Project-wide ddot knowledge graph, assembled from {@link DdotTripleIndex} and
 * queried through the javalib K-API. The query layer of the integration
 * architecture (see {@code spec-models.adoc}): IntelliJ owns per-file extraction
 * and storage; this service assembles the per-file triples into a {@link Kgraph}
 * and answers graph-shaped questions a flat index can't (objects used with a
 * relation, and — via the graph — type-aware / inferred queries).
 *
 * <p><b>Incremental.</b> The graph is built once (full), then maintained per file:
 * a {@code VFS_CHANGES} listener records changed ddot/Markdown/AsciiDoc files, and
 * the next query applies {@link Kgraph#removeBySource(String)} + re-ingest of just
 * those files (dropping each file's old contribution while shared predicate vocab
 * survives), then re-runs {@link Kgraph#inference()}. This marries IntelliJ's
 * per-file re-indexing to the KB's per-source update — no whole-project rebuild on
 * edit.
 *
 * <p>All graph access is synchronized; the mem {@code Kgraph} does not guard reads.
 */
@Service(Service.Level.PROJECT)
public final class DdotKgraphService implements Disposable {

    private final Project project;
    /** URLs of ddot-hosting files changed since the last query; applied as deltas. */
    private final Set<String> dirty = ConcurrentHashMap.newKeySet();
    private @Nullable Kgraph graph;

    public DdotKgraphService(@NotNull Project project) {
        this.project = project;
        project.getMessageBus().connect(this).subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                for (VFileEvent event : events) {
                    VirtualFile file = event.getFile();
                    if (file != null && DdotEmbeds.isSupportedExtension(file.getExtension())) {
                        dirty.add(file.getUrl());
                    }
                }
            }
        });
    }

    public static DdotKgraphService getInstance(@NotNull Project project) {
        return project.getService(DdotKgraphService.class);
    }

    @Override
    public void dispose() {
        // Message-bus connection is released via connect(this).
    }

    /**
     * Type-asserting predicates. The Vocabulary Specification's normative names:
     * {@code has type}, its alias {@code hasType}, and the N3-style shortcut
     * {@code a} (declared "is replaced by has type"). {@code type} and
     * {@code is a} are legacy leniency — they cost nothing to keep.
     */
    private static final Set<String> TYPE_RELATIONS = Set.of("has type", "hasType", "a", "type", "is a");

    /** Objects linked anywhere in the project by {@code relation}. Empty when indexes aren't ready. */
    public synchronized @NotNull Set<String> objectsForRelation(@NotNull String relation) {
        if (relation.isEmpty()) return Set.of();
        Kgraph g = currentGraph();
        if (g == null) return Set.of();
        // A type-asserting relation → the project's known type names (across all aliases).
        if (TYPE_RELATIONS.contains(relation)) {
            return objectsOf(g.pattern(typePattern(new Kgraph.Variable("s"), new Kgraph.Variable("o"))));
        }
        return objectsOf(g.pattern(new Kgraph.Pattern(
                new Kgraph.Variable("s"), new Kgraph.Constant(relation), new Kgraph.Variable("o"))));
    }

    /**
     * Relations used anywhere on entities that share a type with {@code subject} —
     * "relations appropriate for this kind of thing". Benefits from inference: a
     * subject of a subtype also counts as its supertype, so supertype relations are
     * included. Empty if the subject has no known type.
     */
    public synchronized @NotNull Set<String> relationsForSubjectType(@NotNull String subject) {
        if (subject.isEmpty()) return Set.of();
        Kgraph g = currentGraph();
        if (g == null) return Set.of();
        Set<String> types = objectsOf(g.pattern(typePattern(new Kgraph.Constant(subject), new Kgraph.Variable("o"))));
        if (types.isEmpty()) return Set.of();

        Set<String> relations = new HashSet<>();
        for (String type : types) {
            for (String peer : subjectsOf(g.pattern(typePattern(new Kgraph.Variable("e"), new Kgraph.Constant(type))))) {
                for (Kedge edge : g.pattern(new Kgraph.Pattern(
                        new Kgraph.Constant(peer), new Kgraph.Variable("p"), new Kgraph.Variable("o")))) {
                    String predicate = predicateOf(edge);
                    if (predicate != null && !TYPE_RELATIONS.contains(predicate) && !"links to".equals(predicate)) {
                        relations.add(predicate);
                    }
                }
            }
        }
        return relations;
    }

    /**
     * Objects of the kind {@code relation} expects — its inferred <em>range</em>.
     * Takes the types of objects already linked by {@code relation}, then returns
     * every entity of those types (so a single {@code works at Acme} where
     * {@code Acme is a Company} surfaces all Companies, including ones never yet
     * linked by {@code relation}). Empty for type relations or when no range type
     * can be inferred — callers union this with {@link #objectsForRelation}.
     */
    public synchronized @NotNull Set<String> objectsOfRelationRange(@NotNull String relation) {
        if (relation.isEmpty() || TYPE_RELATIONS.contains(relation)) return Set.of();
        Kgraph g = currentGraph();
        if (g == null) return Set.of();

        Set<String> objects = objectsOf(g.pattern(new Kgraph.Pattern(
                new Kgraph.Variable("s"), new Kgraph.Constant(relation), new Kgraph.Variable("o"))));
        Set<String> range = new HashSet<>();
        for (String object : objects) {
            range.addAll(objectsOf(g.pattern(typePattern(new Kgraph.Constant(object), new Kgraph.Variable("t")))));
        }
        if (range.isEmpty()) return Set.of();

        Set<String> entities = new HashSet<>();
        for (String type : range) {
            entities.addAll(subjectsOf(g.pattern(typePattern(new Kgraph.Variable("e"), new Kgraph.Constant(type)))));
        }
        return entities;
    }

    private static Kgraph.Pattern typePattern(Kgraph.Slot subject, Kgraph.Slot object) {
        return new Kgraph.Pattern(subject, new Kgraph.OneOf(TYPE_RELATIONS), object);
    }

    private static Set<String> objectsOf(List<Kedge> edges) {
        return endpointIds(edges, Kendpoint.Kdirection.out);
    }

    private static Set<String> subjectsOf(List<Kedge> edges) {
        return endpointIds(edges, Kendpoint.Kdirection.in);
    }

    private static Set<String> endpointIds(List<Kedge> edges, Kendpoint.Kdirection direction) {
        Set<String> ids = new HashSet<>();
        for (Kedge edge : edges) {
            for (Kendpoint ep : edge.endpoints()) {
                if (ep.kdirection == direction) {
                    ids.add(ep.node.id());
                    break;
                }
            }
        }
        return ids;
    }

    /** Predicate of an edge: the type id of its {@code in} endpoint, or {@code "links to"} (untyped). */
    private static @Nullable String predicateOf(Kedge edge) {
        for (Kendpoint ep : edge.endpoints()) {
            if (ep.kdirection == Kendpoint.Kdirection.in) {
                return ep.type != null ? ep.type.id() : "links to";
            }
        }
        return null;
    }

    /** The assembled graph: full build on first use, per-file deltas after. {@code null} in dumb mode. */
    private @Nullable Kgraph currentGraph() {
        if (DumbService.isDumb(project)) return null;
        try {
            if (graph == null) {
                graph = build();
            } else {
                applyDeltas(graph);
            }
            return graph;
        } catch (IndexNotReadyException ignored) {
            return null;
        }
    }

    private @NotNull Kgraph build() {
        Kgraph g = new Kgraph("project");
        DdotTripleIndex.forEachFile(project, (file, triples) -> ingest(g, file.getUrl(), triples));
        g.inference();
        dirty.clear(); // the full build already covers every file
        return g;
    }

    private void applyDeltas(@NotNull Kgraph g) {
        if (dirty.isEmpty()) return;
        Set<String> batch = new HashSet<>(dirty);
        dirty.removeAll(batch);
        for (String url : batch) {
            g.removeBySource(url); // drop this file's old contribution (inferred edges too)
            VirtualFile file = VirtualFileManager.getInstance().findFileByUrl(url);
            if (file != null && file.isValid()) {
                ingest(g, url, DdotTripleIndex.fileTriples(project, file));
            }
        }
        g.inference(); // regenerate inferences invalidated by the removals
    }

    /** Add one file's triples to {@code g} with provenance keyed by its URL (what removeBySource targets). */
    private static void ingest(@NotNull Kgraph g, @NotNull String url, @NotNull List<DdotTriple> triples) {
        if (triples.isEmpty()) return;
        TripleEvent2Kgraph sink = new TripleEvent2Kgraph(g);
        ISourceLocation location = ISourceLocation.of(ISourceResourceAddress.of(url), -1);
        for (DdotTriple t : triples) {
            sink.accept(new TripleEventFromLine(t.from(), t.type(), t.to(), null, location));
        }
    }
}
