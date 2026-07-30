package com.calpano.ddot.index;

import com.calpano.ddot.completion.DdotKgraphService;
import com.calpano.ddot.completion.DdotRecentRelations;
import com.calpano.ddot.psi.DdotFile;
import com.calpano.ddot.it.token.DdotRole;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * End-to-end check of the project-wide vocabulary index: registration, the
 * value serializer round-trip, the input filter, and {@code projectNames}
 * querying across all three ddot-hosting file types.
 */
public final class DdotEntityIndexTest extends BasePlatformTestCase {

    public void testProjectWideVocabularyAcrossFileTypes() {
        myFixture.addFileToProject("a.ddot", "Alice ..knows.. Bob\n");
        myFixture.addFileToProject("notes/b.md", "intro\n```ddot\nCarol ..likes.. Tea\n```\n");
        myFixture.addFileToProject("docs/c.adoc", "[source,ddot]\n----\nDave ..owns.. Car\n----\n");

        Set<String> predicates = DdotEntityIndex.projectNames(getProject(), DdotRole.PREDICATE);
        assertTrue("predicates from every host type: " + predicates,
                predicates.containsAll(Set.of("knows", "likes", "owns")));

        Set<String> subjects = DdotEntityIndex.projectNames(getProject(), DdotRole.SUBJECT);
        assertTrue("subjects from every host type: " + subjects,
                subjects.containsAll(Set.of("Alice", "Carol", "Dave")));

        Set<String> objects = DdotEntityIndex.projectNames(getProject(), DdotRole.OBJECT);
        assertTrue("objects from every host type: " + objects,
                objects.containsAll(Set.of("Bob", "Tea", "Car")));
    }

    public void testRelationRankingTiers() {
        // Project-only relation (in another file), plus an in-file relation.
        myFixture.addFileToProject("other.ddot", "X ..projrel.. Y\n");
        DdotFile current = (DdotFile) myFixture.configureByText("cur.ddot", "A ..filerel.. B\n");

        // Most-recently-used relations, recorded newest last.
        DdotRecentRelations recents = DdotRecentRelations.getInstance(getProject());
        recents.recordUse("rec_old");
        recents.recordUse("rec_new");

        Map<String, Double> priority = new HashMap<>();
        for (DdotEntityIndex.Ranked r : DdotEntityIndex.ranked(current, DdotRole.PREDICATE)) {
            priority.put(r.name(), r.priority());
        }

        // Tier order: recently-used > in-file > in-project.
        assertNotNull("recent relation present even if not in vocabulary", priority.get("rec_new"));
        assertTrue("recent beats in-file", priority.get("rec_new") > priority.get("filerel"));
        assertTrue("in-file beats in-project", priority.get("filerel") > priority.get("projrel"));
        // Within the recent tier, newer ranks above older.
        assertTrue("newer recent beats older recent", priority.get("rec_new") > priority.get("rec_old"));
    }

    /**
     * Meta names live in their own pools. Before they did, a name used ONLY in a
     * meta position was invisible to completion: the meta slots borrowed the
     * plain P/O pools, and the PSI layer cannot help either (its role model has a
     * single coarse META, so the in-file tier is empty for these slots).
     */
    public void testMetaOnlyNamesAreIndexed() {
        myFixture.addFileToProject("kb.ddot",
                "Alice ..leads.. Eagle ,, ..since.. 2025\n");

        Set<String> metaRelations = DdotEntityIndex.projectNames(getProject(), DdotRole.META_RELATION);
        assertTrue("meta-only relation is offered: " + metaRelations, metaRelations.contains("since"));
        Set<String> metaObjects = DdotEntityIndex.projectNames(getProject(), DdotRole.META_OBJECT);
        assertTrue("meta-only object is offered: " + metaObjects, metaObjects.contains("2025"));

        // The pools compose per the specified query catalog, in both directions:
        // `mr` draws on `mr, p` and `p` draws on `p, mr`, because a meta relation
        // IS a relation. Which pool a name came from decides its RANK, not
        // whether it is offered at all — see testOwnPoolOutranksBorrowedPool.
        assertTrue("plain relations offered for a meta relation: " + metaRelations,
                metaRelations.contains("leads"));
        assertTrue("plain objects offered for a meta object: " + metaObjects,
                metaObjects.contains("Eagle"));
        assertTrue("meta relations offered for a plain relation",
                DdotEntityIndex.projectNames(getProject(), DdotRole.PREDICATE).contains("since"));
        assertTrue("meta objects offered for a plain object",
                DdotEntityIndex.projectNames(getProject(), DdotRole.OBJECT).contains("2025"));

        // Relation-like slots stay inside their own family: offering values where
        // a relation name belongs would be a category error.
        assertFalse("objects are not offered for a relation",
                DdotEntityIndex.projectNames(getProject(), DdotRole.PREDICATE).contains("Eagle"));
        assertFalse("objects are not offered for a meta relation: " + metaRelations,
                metaRelations.contains("Eagle"));
    }

    /** A name from the slot's own pool outranks one borrowed from the fallback pool. */
    public void testOwnPoolOutranksBorrowedPool() {
        myFixture.addFileToProject("kb.ddot",
                "Alice ..leads.. Eagle ,, ..since.. 2025\n");
        DdotFile current = (DdotFile) myFixture.configureByText("cur.ddot", "");

        Map<String, Double> priority = new HashMap<>();
        for (DdotEntityIndex.Ranked r : DdotEntityIndex.ranked(current, DdotRole.META_RELATION)) {
            priority.put(r.name(), r.priority());
        }
        assertNotNull("meta relation offered", priority.get("since"));
        assertNotNull("plain relation offered as fallback", priority.get("leads"));
        assertTrue("meta relation outranks a plain relation: " + priority,
                priority.get("since") > priority.get("leads"));
    }

    /**
     * Key 6 of the ranking spec: within a pool and locality, the more-used name
     * comes first. Counts are corpus-wide, so they must survive the per-file
     * index round-trip and be summed across files.
     */
    public void testFrequencyOrdersWithinATier() {
        myFixture.addFileToProject("a.ddot", String.join("\n",
                "X ..common.. 1",
                "X ..common.. 2",
                "Y ..rare.. 3", ""));
        myFixture.addFileToProject("b.ddot", "Z ..common.. 4\n");
        DdotFile current = (DdotFile) myFixture.configureByText("cur.ddot", "");

        Map<String, Double> priority = new HashMap<>();
        for (DdotEntityIndex.Ranked r : DdotEntityIndex.ranked(current, DdotRole.PREDICATE)) {
            priority.put(r.name(), r.priority());
        }
        // `common` is used 3x across two files, `rare` once — same pool, same
        // locality, so only frequency separates them.
        assertNotNull("frequent relation offered", priority.get("common"));
        assertNotNull("rare relation offered", priority.get("rare"));
        assertTrue("more-used relation ranks first: " + priority,
                priority.get("common") > priority.get("rare"));

        // Frequency must not outvote a higher key: `rare` is in this file, so
        // locality still beats the frequency of a project-only name.
        DdotFile withLocal = (DdotFile) myFixture.configureByText("cur2.ddot", "Q ..rare.. 9\n");
        Map<String, Double> local = new HashMap<>();
        for (DdotEntityIndex.Ranked r : DdotEntityIndex.ranked(withLocal, DdotRole.PREDICATE)) {
            local.put(r.name(), r.priority());
        }
        assertTrue("locality outranks frequency: " + local,
                local.get("rare") > local.get("common"));
    }

    /**
     * Ranking key 3 (Recency) applies to relation-LIKE slots — the meta
     * relation as much as the inline relation. Before, only PREDICATE read the
     * recents, so `mr` completion ignored what the author just used.
     */
    public void testRecencyAppliesToMetaRelationSlot() {
        myFixture.addFileToProject("kb.ddot",
                "A ..leads.. B ,, ..since.. 2020 ;; ..until.. 2021\n");
        DdotFile current = (DdotFile) myFixture.configureByText("cur.ddot", "");
        DdotRecentRelations.getInstance(getProject()).recordUse("until");

        Map<String, Double> priority = new HashMap<>();
        for (DdotEntityIndex.Ranked r : DdotEntityIndex.ranked(current, DdotRole.META_RELATION)) {
            priority.put(r.name(), r.priority());
        }
        // Same pool, same locality, same count — only recency separates them.
        assertTrue("recent meta relation ranks first: " + priority,
                priority.get("until") > priority.get("since"));
    }

    /**
     * Ranking key 5 (Locality) is same-FILE, not same-file-and-same-role: a
     * name used anywhere in the current document gets the locality credit in
     * any slot whose catalog admits it.
     */
    public void testLocalityIsPerFileNotPerRole() {
        myFixture.addFileToProject("other.ddot", "Other ..r2.. Carl\n");
        DdotFile current = (DdotFile) myFixture.addFileToProject("cur.ddot", "X ..r.. Bob\n");

        Map<String, Double> priority = new HashMap<>();
        for (DdotEntityIndex.Ranked r : DdotEntityIndex.ranked(current, DdotRole.SUBJECT)) {
            priority.put(r.name(), r.priority());
        }
        // Bob and Carl both come from the OBJECTS pool with count 1; Bob is
        // used in THIS file (as an object), Carl only elsewhere.
        assertNotNull(priority.get("Bob"));
        assertNotNull(priority.get("Carl"));
        assertTrue("same-file name outranks other-file name: " + priority,
                priority.get("Bob") > priority.get("Carl"));
    }

    /** Ranking key 7: ties break case-insensitive alphabetically, then by code point. */
    public void testTieBreakIsCaseInsensitiveAlphabetical() {
        myFixture.addFileToProject("kb.ddot", "s1 ..alpha.. o1\ns2 ..Beta.. o2\ns3 ..gamma.. o3\n");
        DdotFile current = (DdotFile) myFixture.configureByText("cur.ddot", "");
        List<String> tied = new java.util.ArrayList<>();
        for (DdotEntityIndex.Ranked r : DdotEntityIndex.ranked(current, DdotRole.PREDICATE)) {
            if (Set.of("alpha", "Beta", "gamma").contains(r.name())) tied.add(r.name());
        }
        assertEquals(List.of("alpha", "Beta", "gamma"), tied);
    }

    /** The commandNames contract: distinct names, prefix and query stripped, ci-sorted. */
    public void testCommandNamesHarvestedAcrossFiles() {
        myFixture.addFileToProject("a.ddot", "!!this ..status.. active\n");
        myFixture.addFileToProject("b.md", "```ddot\ns ..r.. ddot.it/Foo?q=1\n```\n");
        assertEquals(List.of("Foo", "this"), DdotEntityIndex.commandNames(getProject()));
    }

    public void testRecentSlotsAreCappedAtThree() {
        DdotRecentRelations recents = DdotRecentRelations.getInstance(getProject());
        for (String rel : List.of("c1", "c2", "c3", "c4", "c5")) recents.recordUse(rel);
        // Only the 3 most recent are surfaced.
        assertEquals(List.of("c5", "c4", "c3"), recents.recent(DdotEntityIndex.RECENT_SLOTS));
    }

    public void testKgraphObjectsForRelationAcrossFiles() {
        myFixture.addFileToProject("a.ddot", "Alice ..likes.. Tea\nAlice ..owns.. Car\n");
        myFixture.addFileToProject("b.md", "```ddot\nBob ..likes.. Coffee\n```\n");

        // The project Kgraph (assembled from the triple index) answers the join.
        DdotKgraphService kb = DdotKgraphService.getInstance(getProject());
        assertEquals(Set.of("Tea", "Coffee"), kb.objectsForRelation("likes"));
        assertEquals(Set.of("Car"), kb.objectsForRelation("owns"));
        assertTrue(kb.objectsForRelation("unheard-of").isEmpty());
    }

    public void testKgraphRebuildsWhenIndexChanges() {
        myFixture.addFileToProject("one.ddot", "S ..rel.. One\n");
        DdotKgraphService kb = DdotKgraphService.getInstance(getProject());
        assertEquals(Set.of("One"), kb.objectsForRelation("rel"));
        // Adding a file is a VFS change → applied as a per-file delta on the next query.
        myFixture.addFileToProject("two.ddot", "S ..rel.. Two\n");
        assertEquals(Set.of("One", "Two"), kb.objectsForRelation("rel"));
    }

    public void testKgraphIncrementalRemovalOnFileEdit() throws Exception {
        PsiFile file = myFixture.addFileToProject("kb.ddot", "Alice ..likes.. Tea\nAlice ..likes.. Coffee\n");
        DdotKgraphService kb = DdotKgraphService.getInstance(getProject());
        assertEquals(Set.of("Tea", "Coffee"), kb.objectsForRelation("likes"));

        // Rewrite the file dropping "Coffee" → removeBySource must drop it from the graph.
        VirtualFile vf = file.getVirtualFile();
        WriteAction.run(() -> VfsUtil.saveText(vf, "Alice ..likes.. Tea\n"));
        assertEquals(Set.of("Tea"), kb.objectsForRelation("likes"));
    }

    public void testKnownTypesAcrossAliases() {
        myFixture.addFileToProject("kb.ddot", "Alice ..is a.. Person\nBob ..type.. Project\n");
        // A type-asserting relation yields the project's type names across every alias.
        assertEquals(Set.of("Person", "Project"),
                DdotKgraphService.getInstance(getProject()).objectsForRelation("is a"));
    }

    public void testTypeAwareRelationsFromSameType() {
        myFixture.addFileToProject("kb.ddot", String.join("\n",
                "Alice ..is a.. Person",
                "Alice ..email.. a@x.com",
                "Bob ..is a.. Person", ""));
        // Bob is a Person; a Person (Alice) uses "email" → suggest it for Bob; the type
        // relation itself is not suggested.
        Set<String> rels = DdotKgraphService.getInstance(getProject()).relationsForSubjectType("Bob");
        assertTrue("type-peer relation: " + rels, rels.contains("email"));
        assertFalse(rels.contains("is a"));
    }

    public void testRangeInferenceSuggestsTypeAppropriateObjects() {
        myFixture.addFileToProject("kb.ddot", String.join("\n",
                "Alice ..works at.. Acme",   // the only "works at" usage
                "Acme ..is a.. Company",
                "Globex ..is a.. Company",
                "Initech ..is a.. Company",
                "Carol ..is a.. Person", ""));
        // Range of "works at" inferred from Acme → Company; suggest every Company,
        // including Globex/Initech never linked by "works at", but not Carol (a Person).
        assertEquals(Set.of("Acme", "Globex", "Initech"),
                DdotKgraphService.getInstance(getProject()).objectsOfRelationRange("works at"));
    }

    public void testTypeAwareRelationsViaInference() {
        myFixture.addFileToProject("kb.ddot", String.join("\n",
                "Person ..has subtype.. Student",
                "Alice ..is a.. Student",
                "Bob ..is a.. Person",
                "Bob ..phone.. 555", ""));
        // Alice is a Student ⟹ (inferred) a Person, so a Person-peer's "phone" applies to Alice.
        Set<String> rels = DdotKgraphService.getInstance(getProject()).relationsForSubjectType("Alice");
        assertTrue("inferred supertype relation: " + rels, rels.contains("phone"));
    }
}
