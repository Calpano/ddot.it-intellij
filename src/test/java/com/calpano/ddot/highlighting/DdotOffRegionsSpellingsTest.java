package com.calpano.ddot.highlighting;

import com.calpano.ddot.psi.DdotOffRegions;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Spec conformance of {@link DdotOffRegions} (Parse Spec, "Pre-Parse
 * Commands"): all four command spellings, recognition anywhere on a line,
 * exact name match, and the directive line NOT being part of the excluded
 * region.
 */
public class DdotOffRegionsSpellingsTest {

    @Test
    public void bangSpelling() {
        DdotOffRegions.Result r = DdotOffRegions.analyze(
                "a ..b.. c\n!!off\nhidden\n!!on\nd ..e.. f");
        assertFalse(r.isInactive(0));
        assertTrue(r.isDirective(1));
        assertFalse("directive line is not part of the excluded region", r.isInactive(1));
        assertTrue(r.isInactive(2));
        assertTrue(r.isDirective(3));
        assertFalse(r.isInactive(3));
        assertFalse(r.isInactive(4));
        assertEquals(1, r.spans().size());
        assertEquals(1, r.spans().get(0).startLine());
        assertEquals(3, r.spans().get(0).endLine());
    }

    @Test
    public void allFourSpellingsToggle() {
        for (String off : new String[]{"!!off", "ddot.it/off", "http://ddot.it/off", "https://ddot.it/off"}) {
            for (String on : new String[]{"!!on", "ddot.it/on", "http://ddot.it/on", "https://ddot.it/on"}) {
                DdotOffRegions.Result r = DdotOffRegions.analyze(off + "\nhidden\n" + on + "\nvisible");
                assertTrue(off + " should be a directive", r.isDirective(0));
                assertTrue(off + "/" + on + ": line between should be inactive", r.isInactive(1));
                assertTrue(on + " should be a directive", r.isDirective(2));
                assertFalse(off + "/" + on + ": line after on should be active", r.isInactive(3));
            }
        }
    }

    @Test
    public void recognizedAnywhereOnALine() {
        // The common spelling: inside a host-language comment.
        DdotOffRegions.Result r = DdotOffRegions.analyze(
                "<!-- ddot.it/off -->\nhidden ..still.. hidden\n<!-- ddot.it/on -->\nx ..y.. z");
        assertTrue(r.isDirective(0));
        assertFalse(r.isInactive(0));
        assertTrue(r.isInactive(1));
        assertTrue(r.isDirective(2));
        assertFalse(r.isInactive(3));
    }

    @Test
    public void hashCommentSpelling() {
        DdotOffRegions.Result r = DdotOffRegions.analyze("# !!off\nhidden\n# !!on\nvisible");
        assertTrue(r.isDirective(0));
        assertTrue(r.isInactive(1));
        assertTrue(r.isDirective(2));
        assertFalse(r.isInactive(3));
    }

    @Test
    public void exactNameMatch() {
        // `!!office` is not `!!off`.
        DdotOffRegions.Result r = DdotOffRegions.analyze("!!office\nstill ..fully.. active");
        assertFalse(r.isDirective(0));
        assertFalse(r.isInactive(1));
        assertTrue(r.spans().isEmpty());
    }

    @Test
    public void tripleBangIsNotACommand() {
        // `!!!off` — the bang run has length 3, which is not EM2; it is text.
        DdotOffRegions.Result r = DdotOffRegions.analyze("!!!off\nstill ..fully.. active");
        assertFalse(r.isDirective(0));
        assertFalse(r.isInactive(1));
        assertTrue(r.spans().isEmpty());
    }

    @Test
    public void unterminatedOffRunsToEndOfFile() {
        DdotOffRegions.Result r = DdotOffRegions.analyze("visible\n!!off\nhidden1\nhidden2");
        assertTrue(r.isDirective(1));
        assertTrue(r.isInactive(2));
        assertTrue(r.isInactive(3));
        assertEquals(1, r.spans().size());
        assertEquals(3, r.spans().get(0).endLine());
    }

    @Test
    public void blankLinesInsideRegionAreInactive() {
        DdotOffRegions.Result r = DdotOffRegions.analyze("!!off\n\nhidden\n\n!!on");
        assertTrue(r.isInactive(1));
        assertTrue(r.isInactive(3));
    }

    @Test
    public void commandsInsideBlockBodyAreInert() {
        // Parse Spec: pre-parse commands are not active inside a !!block body.
        DdotOffRegions.Result r = DdotOffRegions.analyze(
                "s ..quote.. !!block?end=END\n!!off\nEND\nactive ..still.. yes");
        assertFalse("!!off inside a block body must not toggle", r.isDirective(1));
        assertFalse(r.isInactive(3));
        assertTrue(r.spans().isEmpty());
    }
}
