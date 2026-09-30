package com.calpano.ddot.completion;

import com.calpano.ddot.index.DdotEmbeds;
import it.ddot.core.event.DdotEvent;
import it.ddot.core.event.DdotTriple;
import com.calpano.ddot.index.DdotTripleIndex;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import it.ddot.core.kbase.Kendpoint;
import it.ddot.core.kbase.inmem.Kedge;
import it.ddot.core.kbase.inmem.Kgraph;
import it.ddot.core.source.ISnippet;
import it.ddot.core.source.ISourceLocation;
import it.ddot.core.io.handler.TripleEvent2Kgraph;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
 * a {@code VFS_CHANGES} listener and a document listener record changed (or unsaved-edited)
 * ddot/Markdown/AsciiDoc files, and
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
                    markDirty(file);
                }
            }
        });
        // Unsaved edits count too: the popup and completion see a triple as soon as it is typed.
        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent event) {
                markDirty(FileDocumentManager.getInstance().getFile(event.getDocument()));
            }
        }, this);
    }

    private void markDirty(@Nullable VirtualFile file) {
        if (file != null && DdotEmbeds.isSupportedExtension(file.getExtension())) {
            dirty.add(file.getUrl());
        }
    }

    public static DdotKgraphService getInstance(@NotNull Project project) {
        return project.getService(DdotKgraphService.class);
    }

    @Override
    public void dispose() {
        // Message-bus connection and document listener are released via this disposable.
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

    /** Where a triple is stated: file URL and 1-based line. */
    public record Location(@NotNull String url, int line) {}

    /** One relation of an entity and the entities on its other end, both sorted. */
    public record RelationGroup(@NotNull String relation, @NotNull List<String> entities) {}

    /** Subject and object of a triple whose relation is known from context. */
    public record Pair(@NotNull String subject, @NotNull String object) {}

    /**
     * What the graph states about an entity: {@code outgoing} holds {@code (entity, relation, x)},
     * {@code incoming} the inverse direction {@code (x, relation, entity)}, and {@code asRelation} the
     * triples {@code (x, entity, y)} that use the entity as their relation. Inferred edges are left out —
     * they are stated nowhere, so there is no place to navigate to.
     */
    public record EntityFacts(@NotNull List<RelationGroup> outgoing, @NotNull List<RelationGroup> incoming,
                              @NotNull List<Pair> asRelation) {
        public boolean isEmpty() {
            return outgoing.isEmpty() && incoming.isEmpty() && asRelation.isEmpty();
        }
    }

    /** Stated triples around {@code entity}. Empty when indexes aren't ready. */
    public synchronized @NotNull EntityFacts facts(@NotNull String entity) {
        Kgraph g = entity.isEmpty() ? null : currentGraph();
        if (g == null) return new EntityFacts(List.of(), List.of(), List.of());
        Map<String, Set<String>> out = new TreeMap<>();
        for (Kedge e : g.pattern(new Kgraph.Pattern(
                new Kgraph.Constant(entity), new Kgraph.Variable("p"), new Kgraph.Variable("o")))) {
            if (e.isInferred()) continue;
            out.computeIfAbsent(Kgraph.predicateIdOf(e), k -> new TreeSet<>()).add(Kgraph.objectIdOf(e));
        }
        Map<String, Set<String>> in = new TreeMap<>();
        for (Kedge e : g.pattern(new Kgraph.Pattern(
                new Kgraph.Variable("s"), new Kgraph.Variable("p"), new Kgraph.Constant(entity)))) {
            if (e.isInferred()) continue;
            in.computeIfAbsent(Kgraph.predicateIdOf(e), k -> new TreeSet<>()).add(Kgraph.subjectIdOf(e));
        }
        Set<Pair> asRelation = new TreeSet<>(Comparator.comparing(Pair::subject).thenComparing(Pair::object));
        for (Kedge e : g.pattern(new Kgraph.Pattern(
                new Kgraph.Variable("s"), new Kgraph.Constant(entity), new Kgraph.Variable("o")))) {
            if (e.isInferred()) continue;
            asRelation.add(new Pair(Kgraph.subjectIdOf(e), Kgraph.objectIdOf(e)));
        }
        return new EntityFacts(groups(out), groups(in), List.copyOf(asRelation));
    }

    /**
     * Every place a triple matching the pattern is stated, sorted by file and line. A {@code null} slot is a
     * wildcard, so {@code (foo, rel, null)} finds all {@code foo ..rel.. x} statements.
     */
    public synchronized @NotNull List<Location> locations(@Nullable String subject, @Nullable String relation,
                                                          @Nullable String object) {
        Kgraph g = currentGraph();
        if (g == null) return List.of();
        Set<Location> found = new TreeSet<>(Comparator.comparing(Location::url).thenComparingInt(Location::line));
        for (Kedge e : g.pattern(new Kgraph.Pattern(slot(subject, "s"), slot(relation, "p"), slot(object, "o")))) {
            if (e.isInferred()) continue;
            for (ISourceLocation loc : e.sourceLocations()) {
                found.add(new Location(loc.sourceResource().resourceUri(), loc.lineNumber()));
            }
        }
        return new ArrayList<>(found);
    }

    private static Kgraph.Slot slot(@Nullable String value, String variable) {
        return value == null ? new Kgraph.Variable(variable) : new Kgraph.Constant(value);
    }

    private static List<RelationGroup> groups(Map<String, Set<String>> byRelation) {
        List<RelationGroup> groups = new ArrayList<>();
        byRelation.forEach((relation, entities) -> groups.add(new RelationGroup(relation, List.copyOf(entities))));
        return groups;
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
            if (graph == null) graph = build();
            applyDeltas(graph);
            return graph;
        } catch (IndexNotReadyException ignored) {
            return null;
        }
    }

    private @NotNull Kgraph build() {
        Kgraph g = new Kgraph("project");
        DdotTripleIndex.forEachFile(project, (file, triples) -> ingest(g, file.getUrl(), triples));
        g.inference();
        dirty.clear(); // the full build already covers every saved file …
        for (Document doc : FileDocumentManager.getInstance().getUnsavedDocuments()) {
            markDirty(FileDocumentManager.getInstance().getFile(doc)); // … unsaved ones follow as deltas
        }
        return g;
    }

    private void applyDeltas(@NotNull Kgraph g) {
        if (dirty.isEmpty()) return;
        Set<String> batch = new HashSet<>(dirty);
        dirty.removeAll(batch);
        for (String url : batch) {
            g.removeBySource(url); // drop this file's old contribution (inferred edges too)
            VirtualFile file = VirtualFileManager.getInstance().findFileByUrl(url);
            if (file != null && file.isValid() && ProjectFileIndex.getInstance(project).isInContent(file)) {
                ingest(g, url, currentTriples(file));
            }
        }
        g.inference(); // regenerate inferences invalidated by the removals
    }

    /** A file's triples as currently edited: from its unsaved document if there is one, else from the index. */
    private @NotNull List<DdotTripleIndex.Located> currentTriples(@NotNull VirtualFile file) {
        FileDocumentManager fdm = FileDocumentManager.getInstance();
        Document doc = fdm.getCachedDocument(file);
        if (doc != null && fdm.isDocumentUnsaved(doc)) {
            return DdotTripleIndex.extract(file.getExtension(), doc.getImmutableCharSequence(), file.getName());
        }
        return DdotTripleIndex.fileTriples(project, file);
    }

    /**
     * Add one file's triples to {@code g} with provenance keyed by its URL (what removeBySource targets) and the
     * line each triple stands on (what {@link #locations} navigates to).
     */
    private static void ingest(@NotNull Kgraph g, @NotNull String url, @NotNull List<DdotTripleIndex.Located> triples) {
        ISnippet snippet = ISnippet.of(url);
        if (triples.isEmpty()) return;
        TripleEvent2Kgraph sink = new TripleEvent2Kgraph(g);
        for (DdotTripleIndex.Located l : triples) {
            DdotTriple t = l.triple();
            sink.accept(new DdotEvent(snippet, t.from(), t.type(), t.to(), l.line()));
        }
    }
}
