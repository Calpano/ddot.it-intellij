package com.calpano.ddot.completion;

import com.calpano.ddot.index.DdotTripleIndex;
import com.calpano.kbase.KIds;
import com.calpano.kbase.KTriples;
import com.calpano.kbase.index.TripleIndex;
import com.calpano.kbase.inmem.Kedge;
import com.calpano.kbase.inmem.Kgraph;
import com.calpano.kbase.io.TripleEvent2Kgraph;
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
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.VirtualFileVisitor;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent;
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent;
import com.calpano.kbase.cds.Cds;
import com.calpano.kbase.cds.CdsReasoner;
import com.calpano.kbase.cds.Triple;
import it.ddot.core.embed.DdotEmbeds;
import it.ddot.core.embed.LocatedTriple;
import it.ddot.core.event.DdotEvent;
import it.ddot.core.event.DdotTriple;
import com.calpano.kbase.lint.DdotAnalysis;
import it.ddot.core.lint.Finding;
import it.ddot.core.source.ISnippet;
import it.ddot.core.source.ISourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Project-wide ddot knowledge graph, assembled from {@link DdotTripleIndex} into the
 * {@code com.calpano.kbase} model. The query layer of the integration architecture (see
 * {@code spec-models.adoc}): IntelliJ owns per-file extraction and storage; this service assembles the
 * per-file triples into a {@link Kgraph} and answers graph-shaped questions a flat index can't (objects
 * used with a relation, type-aware and inferred queries).
 *
 * <p><b>Model.</b> The {@link Kgraph} holds only what was stated: the same triple stated twice, in one
 * file or two, is one link with both source locations. A {@link TripleIndex} on top follows the graph and
 * answers triple patterns; its CDS {@linkplain TripleIndex#reasoner() reasoner} answers what the stated
 * triples entail (the {@code has subtype} hierarchy, inverses, aliases) without adding anything to the
 * graph. Item ids are {@link KIds#fromText derived from the text}, so every name crossing this class's API
 * is converted on the way in and out.
 *
 * <p><b>Incremental.</b> The graph is built once (full), then maintained per file: a {@code VFS_CHANGES}
 * listener and a document listener record changed (or unsaved-edited) ddot/Markdown/AsciiDoc files, and
 * the files under a deleted, moved or renamed directory, and the next query applies {@link Kgraph#removeBySource(String)} + re-ingest of just those files. The index
 * follows each change inside the graph's write, and the reasoner brings itself up to date when the
 * graph's revision has moved.
 *
 * <p>The service's own state (dirty set, analysis cache) is guarded by {@code synchronized}; the graph and
 * index guard themselves.
 */
@Service(Service.Level.PROJECT)
public final class DdotKgraphService implements Disposable {

    private final Project project;
    /** URLs of ddot-hosting files changed since the last query; applied as deltas. */
    private final Set<String> dirty = ConcurrentHashMap.newKeySet();
    /**
     * URLs of directories that were deleted, moved or renamed, each ending in {@code /}: every ingested file
     * under one is dirty. The VFS reports only the directory, not the files in it.
     */
    private final Set<String> dirtyDirs = ConcurrentHashMap.newKeySet();
    private @Nullable Kgraph graph;
    /** SPO index and reasoner over {@link #graph}; replaced together with it. */
    private @Nullable TripleIndex index;
    /** Each file's stated triples, as last ingested — what the project-wide checks read. */
    private final Map<String, List<LocatedTriple>> statedByUrl = new HashMap<>();
    /** Bumped on every change to the graph; keys the cached analysis. */
    private long version;
    private long analysedVersion = -1;
    private @Nullable DdotAnalysis analysis;
    /** One file's findings, for the analysis and text they were computed from: injected blocks share them. */
    private record FileFindings(DdotAnalysis analysis, String text, List<Finding> findings) {}
    private final Map<String, FileFindings> findingsByUrl = new HashMap<>();
    /** The names completion offers, for the graph at {@link #vocabularyVersion}. */
    private DdotVocabulary vocabulary = DdotVocabulary.EMPTY;
    private long vocabularyVersion = -1;

    public DdotKgraphService(@NotNull Project project) {
        this.project = project;
        project.getMessageBus().connect(this).subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void before(@NotNull List<? extends VFileEvent> events) {
                // What goes away: the old location, while its URL is still the file's.
                for (VFileEvent event : events) {
                    if (event instanceof VFileDeleteEvent || event instanceof VFileMoveEvent
                            || isRename(event)) {
                        markGone(event.getFile());
                    }
                }
            }

            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                // What is there now: changed content, and created, copied, moved or renamed files.
                for (VFileEvent event : events) {
                    if (event instanceof VFileDeleteEvent) continue;
                    if (event instanceof VFilePropertyChangeEvent && !isRename(event)) continue;
                    if (event instanceof VFileContentChangeEvent) markDirty(event.getFile());
                    else markTree(event.getFile());
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

    private static boolean isRename(@NotNull VFileEvent event) {
        return event instanceof VFilePropertyChangeEvent change
                && VirtualFile.PROP_NAME.equals(change.getPropertyName());
    }

    /** {@code file}, at its current location, is about to go: drop it, or everything under it. */
    private void markGone(@Nullable VirtualFile file) {
        if (file == null) return;
        if (file.isDirectory()) dirtyDirs.add(file.getUrl() + "/");
        else markDirty(file);
    }

    /** {@code file} is new at its location: read it, or every file under it. */
    private void markTree(@Nullable VirtualFile file) {
        if (file == null || !file.isValid()) return;
        if (!file.isDirectory()) {
            markDirty(file);
            return;
        }
        VfsUtilCore.visitChildrenRecursively(file, new VirtualFileVisitor<Void>() {
            @Override
            public boolean visitFile(@NotNull VirtualFile child) {
                if (!child.isDirectory()) markDirty(child);
                return true;
            }
        });
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
    public synchronized void dispose() {
        // Message-bus connection and document listener are released via this disposable.
        if (index != null) index.close();
    }

    /**
     * Type-asserting predicates. The Vocabulary Specification's normative names:
     * {@code has type}, its alias {@code hasType}, and the N3-style shortcut
     * {@code a} (declared "is replaced by has type"). {@code type} and
     * {@code is a} are legacy leniency — they cost nothing to keep. The CDS reasoner
     * knows only {@code has type}, so stated lookups match all of them.
     */
    private static final Set<String> TYPE_RELATIONS = Set.of(Cds.HAS_TYPE, "hasType", "a", "type", "is a");
    private static final Set<String> TYPE_RELATION_IDS =
            TYPE_RELATIONS.stream().map(KIds::fromText).collect(Collectors.toUnmodifiableSet());

    /** Objects linked anywhere in the project by {@code relation}. Empty when indexes aren't ready. */
    public synchronized @NotNull Set<String> objectsForRelation(@NotNull String relation) {
        if (relation.isEmpty()) return Set.of();
        TripleIndex ix = currentIndex();
        if (ix == null) return Set.of();
        // A type-asserting relation → the project's known type names (across all aliases).
        if (TYPE_RELATIONS.contains(relation)) {
            return texts(stated(ix, var("s"), new TripleIndex.OneOf(TYPE_RELATION_IDS), var("o")), KTriples.Spo::o);
        }
        return texts(stated(ix, var("s"), constant(relation), var("o")), KTriples.Spo::o);
    }

    /**
     * Relations used anywhere on entities that share a type with {@code subject} —
     * "relations appropriate for this kind of thing". Uses the reasoner: a subject of a
     * subtype also counts as an instance of its supertype, so supertype relations are
     * included. Empty if the subject has no known type.
     */
    public synchronized @NotNull Set<String> relationsForSubjectType(@NotNull String subject) {
        if (subject.isEmpty()) return Set.of();
        TripleIndex ix = currentIndex();
        if (ix == null) return Set.of();
        Set<String> types = typesOf(ix, subject);
        if (types.isEmpty()) return Set.of();

        Set<String> relations = new HashSet<>();
        for (String type : types) {
            for (String peer : instancesOf(ix, type)) {
                for (KTriples.Spo t : stated(ix, constant(peer), var("p"), var("o"))) {
                    String predicate = KIds.toText(t.p());
                    if (!TYPE_RELATIONS.contains(predicate) && !Cds.LINKS_TO.equals(predicate)) {
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
        TripleIndex ix = currentIndex();
        if (ix == null) return Set.of();

        Set<String> range = new HashSet<>();
        for (String object : texts(stated(ix, var("s"), constant(relation), var("o")), KTriples.Spo::o)) {
            range.addAll(typesOf(ix, object));
        }
        Set<String> entities = new HashSet<>();
        for (String type : range) entities.addAll(instancesOf(ix, type));
        return entities;
    }

    /**
     * The names stated in the project, by position and with counts, rebuilt only after the graph changed.
     * Empty when indexes aren't ready.
     */
    public synchronized @NotNull DdotVocabulary vocabulary() {
        if (currentIndex() == null) return DdotVocabulary.EMPTY;
        if (vocabularyVersion != version) {
            vocabulary = DdotVocabulary.of(graph, Set.copyOf(statedByUrl.keySet()));
            vocabularyVersion = version;
        }
        return vocabulary;
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
     * triples {@code (x, entity, y)} that use the entity as their relation. Only stated triples — what
     * the reasoner entails is stated nowhere, so there is no place to navigate to.
     */
    public record EntityFacts(@NotNull List<RelationGroup> outgoing, @NotNull List<RelationGroup> incoming,
                              @NotNull List<Pair> asRelation) {
        public boolean isEmpty() {
            return outgoing.isEmpty() && incoming.isEmpty() && asRelation.isEmpty();
        }
    }

    /** Stated triples around {@code entity}. Empty when indexes aren't ready. */
    public synchronized @NotNull EntityFacts facts(@NotNull String entity) {
        TripleIndex ix = entity.isEmpty() ? null : currentIndex();
        if (ix == null) return new EntityFacts(List.of(), List.of(), List.of());
        Map<String, Set<String>> out = new TreeMap<>();
        for (KTriples.Spo t : stated(ix, constant(entity), var("p"), var("o"))) {
            out.computeIfAbsent(KIds.toText(t.p()), k -> new TreeSet<>()).add(KIds.toText(t.o()));
        }
        Map<String, Set<String>> in = new TreeMap<>();
        for (KTriples.Spo t : stated(ix, var("s"), var("p"), constant(entity))) {
            in.computeIfAbsent(KIds.toText(t.p()), k -> new TreeSet<>()).add(KIds.toText(t.s()));
        }
        Set<Pair> asRelation = new TreeSet<>(Comparator.comparing(Pair::subject).thenComparing(Pair::object));
        for (KTriples.Spo t : stated(ix, var("s"), constant(entity), var("o"))) {
            asRelation.add(new Pair(KIds.toText(t.s()), KIds.toText(t.o())));
        }
        return new EntityFacts(groups(out), groups(in), List.copyOf(asRelation));
    }

    /**
     * The project's ddot.it warnings ({@link DdotAnalysis}), recomputed only after the graph changed.
     * {@code null} when indexes aren't ready.
     */
    public synchronized @Nullable DdotAnalysis analysis() {
        if (currentIndex() == null) return null;
        if (analysis == null || analysedVersion != version) {
            analysis = DdotAnalysis.of(statedByUrl);
            analysedVersion = version;
            findingsByUrl.clear();
        }
        return analysis;
    }

    /**
     * Every warning for one host file, as {@link DdotAnalysis#findings} gives them, cached for the text
     * they were computed from: each ddot block of a Markdown file asks for its host's findings.
     * Without indexes, only the per-file checks.
     */
    public synchronized @NotNull List<Finding> findings(@NotNull String url, @Nullable String extension,
                                                         @NotNull String text) {
        DdotAnalysis current = analysis();
        if (current == null) return DdotAnalysis.fileFindings(url, extension, text);
        FileFindings cached = findingsByUrl.get(url);
        if (cached != null && cached.analysis() == current && cached.text().equals(text)) return cached.findings();
        List<Finding> found = current.findings(url, extension, text);
        findingsByUrl.put(url, new FileFindings(current, text, found));
        return found;
    }

    /** Number of stated triples that mention {@code entity} as subject, relation or object. */
    public synchronized int tripleCount(@NotNull String entity) {
        TripleIndex ix = entity.isEmpty() ? null : currentIndex();
        if (ix == null) return 0;
        Set<KTriples.Spo> triples = new HashSet<>();
        triples.addAll(stated(ix, constant(entity), var("p"), var("o")));
        triples.addAll(stated(ix, var("s"), constant(entity), var("o")));
        triples.addAll(stated(ix, var("s"), var("p"), constant(entity)));
        return triples.size();
    }

    /**
     * Every place a triple matching the pattern is stated, sorted by file and line. A {@code null} slot is a
     * wildcard, so {@code (foo, rel, null)} finds all {@code foo ..rel.. x} statements.
     */
    public synchronized @NotNull List<Location> locations(@Nullable String subject, @Nullable String relation,
                                                          @Nullable String object) {
        if ("".equals(subject) || "".equals(relation) || "".equals(object)) return List.of(); // no such item
        TripleIndex ix = currentIndex();
        if (ix == null) return List.of();
        Set<Location> found = new TreeSet<>(Comparator.comparing(Location::url).thenComparingInt(Location::line));
        for (Kedge link : ix.pattern(new TripleIndex.Pattern(slot(subject, "s"), slot(relation, "p"), slot(object, "o")))) {
            for (ISourceLocation loc : link.sourceLocations()) {
                found.add(new Location(loc.sourceResource().resourceUri(), loc.lineNumber()));
            }
        }
        return new ArrayList<>(found);
    }

    // ---- Queries over the index and reasoner ---------------------------------

    private static TripleIndex.Variable var(String name) {
        return new TripleIndex.Variable(name);
    }

    /** A slot matching the item whose text is {@code text}. */
    private static TripleIndex.Constant constant(String text) {
        return new TripleIndex.Constant(KIds.fromText(text));
    }

    private static TripleIndex.Slot slot(@Nullable String text, String variable) {
        return text == null ? var(variable) : constant(text);
    }

    /** The distinct stated triples matching a pattern, as ids. */
    private static Set<KTriples.Spo> stated(TripleIndex ix, TripleIndex.Slot s, TripleIndex.Slot p, TripleIndex.Slot o) {
        Set<KTriples.Spo> triples = new LinkedHashSet<>();
        for (TripleIndex.TripleMatch m : ix.matchTriples(new TripleIndex.Pattern(s, p, o))) triples.add(m.triple());
        return triples;
    }

    /** One position of each triple, as text. */
    private static Set<String> texts(Set<KTriples.Spo> triples, Function<KTriples.Spo, String> position) {
        Set<String> texts = new HashSet<>();
        for (KTriples.Spo t : triples) texts.add(KIds.toText(position.apply(t)));
        return texts;
    }

    /**
     * The types of {@code entity}: those stated with any {@link #TYPE_RELATIONS type relation}, those the
     * reasoner entails for {@code has type}, and every supertype of these.
     */
    private static Set<String> typesOf(TripleIndex ix, String entity) {
        CdsReasoner reasoner = ix.reasoner();
        Set<String> direct = texts(stated(ix, constant(entity), new TripleIndex.OneOf(TYPE_RELATION_IDS), var("t")),
                KTriples.Spo::o);
        for (Triple t : reasoner.match(entity, Cds.HAS_TYPE, null)) direct.add(t.object());
        Set<String> types = new HashSet<>(direct);
        for (String type : direct) {
            for (Triple t : reasoner.match(type, Cds.HAS_SUPERTYPE, null)) types.add(t.object());
        }
        return types;
    }

    /**
     * The entities of {@code type}: those typed with it or with one of its subtypes, by any
     * {@link #TYPE_RELATIONS type relation}, or as the reasoner entails.
     */
    private static Set<String> instancesOf(TripleIndex ix, String type) {
        CdsReasoner reasoner = ix.reasoner();
        Set<String> types = new HashSet<>(Set.of(type));
        for (Triple t : reasoner.match(type, Cds.HAS_SUBTYPE, null)) types.add(t.object());
        Set<String> instances = new HashSet<>();
        for (String t : types) {
            instances.addAll(texts(stated(ix, var("e"), new TripleIndex.OneOf(TYPE_RELATION_IDS), constant(t)),
                    KTriples.Spo::s));
            for (Triple entailed : reasoner.match(null, Cds.HAS_TYPE, t)) instances.add(entailed.subject());
        }
        return instances;
    }

    private static List<RelationGroup> groups(Map<String, Set<String>> byRelation) {
        List<RelationGroup> groups = new ArrayList<>();
        byRelation.forEach((relation, entities) -> groups.add(new RelationGroup(relation, List.copyOf(entities))));
        return groups;
    }

    /**
     * The index over the assembled graph: full build on first use, per-file deltas after. {@code null} in
     * dumb mode.
     */
    private @Nullable TripleIndex currentIndex() {
        if (DumbService.isDumb(project)) return null;
        try {
            if (graph == null) build();
            applyDeltas(graph);
            return index;
        } catch (IndexNotReadyException ignored) {
            return null;
        }
    }

    private void build() {
        if (index != null) index.close();
        Kgraph g = new Kgraph("project");
        statedByUrl.clear();
        version++;
        DdotTripleIndex.forEachFile(project, (file, triples) -> ingest(g, file.getUrl(), triples));
        // Indexing once after the bulk read is cheaper than following every single link added.
        index = new TripleIndex(g);
        graph = g;
        dirty.clear(); // the full build already covers every saved file …
        dirtyDirs.clear();
        for (Document doc : FileDocumentManager.getInstance().getUnsavedDocuments()) {
            markDirty(FileDocumentManager.getInstance().getFile(doc)); // … unsaved ones follow as deltas
        }
    }

    /** Re-reads the dirty files. The index follows the graph; the reasoner catches up on its next question. */
    private void applyDeltas(@NotNull Kgraph g) {
        if (!dirtyDirs.isEmpty()) {
            Set<String> dirs = new HashSet<>(dirtyDirs);
            dirtyDirs.removeAll(dirs);
            for (String url : statedByUrl.keySet()) {
                for (String dir : dirs) {
                    if (url.startsWith(dir)) dirty.add(url);
                }
            }
        }
        if (dirty.isEmpty()) return;
        Set<String> batch = new HashSet<>(dirty);
        dirty.removeAll(batch);
        version++;
        for (String url : batch) {
            g.removeBySource(url); // drop this file's old contribution
            statedByUrl.remove(url);
            VirtualFile file = VirtualFileManager.getInstance().findFileByUrl(url);
            if (file != null && file.isValid() && ProjectFileIndex.getInstance(project).isInContent(file)) {
                ingest(g, url, currentTriples(file));
            }
        }
    }

    /** A file's triples as currently edited: from its unsaved document if there is one, else from the index. */
    private @NotNull List<LocatedTriple> currentTriples(@NotNull VirtualFile file) {
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
    private void ingest(@NotNull Kgraph g, @NotNull String url, @NotNull List<LocatedTriple> triples) {
        if (triples.isEmpty()) return;
        statedByUrl.put(url, triples);
        addTriples(g, url, triples);
    }

    /** Adds one file's triples, with their meta, to {@code g}, each with its file URL and line as provenance. */
    public static void addTriples(@NotNull Kgraph g, @NotNull String url, @NotNull List<LocatedTriple> triples) {
        ISnippet snippet = ISnippet.of(url);
        TripleEvent2Kgraph sink = new TripleEvent2Kgraph(g);
        for (LocatedTriple l : triples) {
            DdotTriple t = l.triple();
            DdotEvent event = new DdotEvent(snippet, t.from(), t.type(), t.to(), l.line());
            for (LocatedTriple.Meta m : l.meta()) event.addMeta(m.relation(), m.value());
            sink.accept(event);
        }
    }
}
