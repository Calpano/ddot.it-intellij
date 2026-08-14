package com.calpano.ddot.index;

import it.ddot.core.kbase.IKnowledgeGraph;
import it.ddot.core.kbase.inmem.Kedge;
import it.ddot.core.kbase.inmem.Kgraph;
import it.ddot.core.kbase.inmem.Knode;
import it.ddot.core.source.FileResourceAddress;
import it.ddot.core.source.ISourceLocation;
import org.junit.Test;

import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Proves the bundled {@code ddot.it-javalib} knowledge model actually works on
 * the plugin classpath — graph construction, SPO/prefix pattern queries, and the
 * per-source removal primitive that backs incremental updates. Pure (no IDE
 * platform), so it is a fast guard that the dependency/shading is healthy.
 */
public final class DdotKgraphSmokeTest {

    @Test
    public void kgraphQueryAndRemoveBySourceWorkOnClasspath() {
        Kgraph g = new Kgraph("test");
        Knode alice = g.getOrCreateNodeWithIdAndLabel("Alice");
        Knode bob = g.getOrCreateNodeWithIdAndLabel("Bob");
        Kedge edge = g.biEdge(alice, "knows", bob);

        FileResourceAddress addr = (FileResourceAddress) FileResourceAddress.of(Path.of("/tmp/x.ddot"));
        edge.addSourceLocation(ISourceLocation.of(addr, 1));

        // SPO pattern: ? --knows--> ?
        assertEquals(1, g.pattern(new Kgraph.Pattern(
                new Kgraph.Variable("s"), new Kgraph.Constant("knows"), new Kgraph.Variable("o"))).size());

        // Prefix matcher (the completion-friendly query): ? --kn*--> ?
        assertEquals(1, g.pattern(new Kgraph.Pattern(
                new Kgraph.Variable("s"), new Kgraph.Prefix("kn"), new Kgraph.Variable("o"))).size());

        // Per-source removal — the incremental-update primitive.
        IKnowledgeGraph.RemoveBySourceResult r = g.removeBySource(addr.resourceUri());
        assertEquals(1, r.edgesRemoved());
        assertTrue(g.pattern(new Kgraph.Pattern(
                new Kgraph.Variable("s"), new Kgraph.Constant("knows"), new Kgraph.Variable("o"))).isEmpty());
    }
}
