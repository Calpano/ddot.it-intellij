package com.calpano.ddot.asciidoc;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The sniff that decides whether opening an AsciiDoc file may write our three
 * {@code .rb} files into the user's repo. False negatives cost the user a trip
 * to the Tools menu; false positives put files in a project that never asked
 * for them, so the pattern is pinned here.
 */
public final class DdotAsciidocAutoInstallerTest {

    private static boolean sniff(String text) {
        return DdotAsciidocAutoInstaller.containsDdotBlock(text);
    }

    @Test
    public void recognisesEveryBlockForm() {
        assertTrue(sniff("= Doc\n\n[ddot]\n----\nA ..b.. C\n----\n"));
        assertTrue(sniff("[ddotit]\n----\nA ..b.. C\n----\n"));
        assertTrue(sniff("[source,ddot]\n----\nA ..b.. C\n----\n"));
        assertTrue(sniff("[source,ddot.it]\n----\nA ..b.. C\n----\n"));
        assertTrue(sniff("[source,ddotit]\n----\nA ..b.. C\n----\n"));
    }

    @Test
    public void toleratesSpacingAndCase() {
        assertTrue(sniff("[ source , ddot ]\n----\nA ..b.. C\n----\n"));
        assertTrue(sniff("[SOURCE,DDot]\n----\nA ..b.. C\n----\n"));
    }

    @Test
    public void needsItsOwnLine() {
        // A header only counts as one when it is the whole line — a mention of
        // `[ddot]` inside a sentence is prose, not a block.
        assertFalse(sniff("Write blocks with the [ddot] style attribute.\n"));
        assertTrue(sniff("intro\n[ddot]\n----\nA ..b.. C\n----\n"));
    }

    @Test
    public void ignoresUnrelatedAsciidoc() {
        assertFalse(sniff("= Doc\n\n[source,ruby]\n----\nputs 1\n----\n"));
        assertFalse(sniff("[source,dot]\n----\ndigraph {}\n----\n"));
        assertFalse(sniff("[NOTE]\n====\nnope\n====\n"));
        assertFalse(sniff(""));
    }

    @Test
    public void ignoresLookalikeLanguageNames() {
        assertFalse(sniff("[source,ddotty]\n----\nx\n----\n"));
        assertFalse(sniff("[source,myddot]\n----\nx\n----\n"));
    }
}
