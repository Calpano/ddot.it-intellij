package com.calpano.ddot.index;

import com.calpano.kbase.IKnowledgeGraph;
import com.calpano.kbase.KIds;
import com.calpano.kbase.index.TripleIndex;
import com.calpano.kbase.inmem.Kedge;
import com.calpano.kbase.inmem.Kgraph;
import com.calpano.kbase.inmem.Knode;
import it.ddot.core.source.FileResourceAddress;
import it.ddot.core.source.ISourceLocation;
import org.junit.Test;

import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Proves the bundled {@code com.calpano:kbase} knowledge model actually works on the plugin classpath —
 * graph construction, SPO/prefix pattern queries on the {@link TripleIndex}, the CDS reasoner, and the
 * per-source removal primitive that backs incremental updates. Pure (no IDE platform), so it is a fast
 * guard that the dependency/shading is healthy.
 */
public final class DdotKgraphSmokeTest {

    @Test
    public void kgraphQueryAndRemoveBySourceWorkOnClasspath() {
        Kgraph g = new Kgraph("test");
        TripleIndex index = new TripleIndex(g);
        Knode alice = g.getOrCreateNodeWithIdAndLabel("Alice");
        Knode bob = g.getOrCreateNodeWithIdAndLabel("Bob");
        Kedge edge = g.biEdge(alice, "knows", bob);

        FileResourceAddress addr = (FileResourceAddress) FileResourceAddress.of(Path.of("/tmp/x.ddot"));
        edge.addSourceLocation(ISourceLocation.of(addr, 1));

        TripleIndex.Pattern knows = new TripleIndex.Pattern(
                new TripleIndex.Variable("s"), new TripleIndex.Constant(KIds.fromText("knows")), new TripleIndex.Variable("o"));
        // SPO pattern: ? --knows--> ?
        assertEquals(1, index.pattern(knows).size());

        // Prefix matcher (the completion-friendly query): ? --kn*--> ?
        assertEquals(1, index.pattern(new TripleIndex.Pattern(
                new TripleIndex.Variable("s"), new TripleIndex.Prefix("kn"), new TripleIndex.Variable("o"))).size());

        // The CDS reasoner sees the stated triple.
        assertTrue(index.reasoner().holds("Alice", "knows", "Bob"));

        // Per-source removal — the incremental-update primitive; the index follows the graph.
        IKnowledgeGraph.RemoveBySourceResult r = g.removeBySource(addr.resourceUri());
        assertEquals(1, r.edgesRemoved());
        assertTrue(index.pattern(knows).isEmpty());
    }
}
