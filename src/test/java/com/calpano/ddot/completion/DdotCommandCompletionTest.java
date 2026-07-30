package com.calpano.ddot.completion;

import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

/**
 * Command completion (Autocomplete Specification, "Command completion"): all
 * four command spellings trigger it, candidates are the four built-ins plus
 * corpus-harvested command names, ranking is slot-appropriate, {@code off}/{@code on}
 * rank last inside a slot and first on a bare line, and completing {@code block}
 * offers {@code ?end=} — but never the marker value.
 */
public final class DdotCommandCompletionTest extends BasePlatformTestCase {

    private List<String> completeIn(String text) {
        myFixture.configureByText("cur.ddot", text);
        myFixture.complete(CompletionType.BASIC);
        return myFixture.getLookupElementStrings();
    }

    public void testBareLineRanksOffOnFirst() {
        // Only the command prefix on the line: the caret is outside any triple,
        // so the pre-parse commands are the sensible ones to offer first.
        List<String> shown = completeIn("!!<caret>");
        assertNotNull("command lookup shown", shown);
        assertOrderedSubset(shown, "!!off", "!!on", "!!this", "!!block");
    }

    public void testObjectSlotRanksBlockFirstAndOffOnLast() {
        List<String> shown = completeIn("s ..r.. !!<caret>");
        assertNotNull(shown);
        // `block` may fill an Object; `off`/`on` are not field values → last.
        assertOrderedSubset(shown, "!!block", "!!this", "!!off", "!!on");
    }

    public void testMetaObjectSlotRanksBlockFirst() {
        List<String> shown = completeIn("s ..r.. o ,, ..k.. !!<caret>");
        assertNotNull(shown);
        assertOrderedSubset(shown, "!!block", "!!this", "!!off", "!!on");
    }

    public void testRelationSlotHasNoSlotAppropriateBuiltins() {
        List<String> shown = completeIn("s ..!!<caret>");
        assertNotNull(shown);
        // No built-in belongs in a relation name: plain alphabetical, off/on last.
        assertOrderedSubset(shown, "!!block", "!!this", "!!off", "!!on");
    }

    public void testCommandsRankAboveValueCandidates() {
        // Ranking key 1, "Kind": with a command prefix typed, commands come first.
        myFixture.addFileToProject("kb.ddot", "Alice ..likes.. Tea\n");
        List<String> shown = completeIn("!!<caret>");
        assertNotNull(shown);
        assertTrue("commands offered: " + shown, shown.contains("!!block"));
        // No value candidate can match a `!!` prefix at all.
        assertFalse("no value candidates behind a command prefix: " + shown, shown.contains("Alice"));
    }

    public void testDdotItSpellingCompletesAndPreservesSpelling() {
        myFixture.configureByText("cur.ddot", "ddot.it/bl<caret>");
        myFixture.complete(CompletionType.BASIC);
        // Single match `block` → auto-inserted, in the spelling the user started.
        myFixture.checkResult("ddot.it/block");
    }

    public void testHttpSpellingCompletes() {
        List<String> shown = completeIn("http://ddot.it/<caret>");
        assertNotNull(shown);
        assertTrue(shown.toString(), shown.contains("http://ddot.it/block"));
        assertTrue(shown.toString(), shown.contains("http://ddot.it/this"));
    }

    public void testHttpsSpellingCompletes() {
        List<String> shown = completeIn("https://ddot.it/<caret>");
        assertNotNull(shown);
        assertTrue(shown.toString(), shown.contains("https://ddot.it/block"));
    }

    public void testBangBangSpellingPreservedOnInsert() {
        myFixture.configureByText("cur.ddot", "s ..r.. !!bl<caret>");
        myFixture.complete(CompletionType.BASIC);
        myFixture.checkResult("s ..r.. !!block");
    }

    public void testBlockOffersEndParamAsFollowUp() {
        // After `block` is completed, `?end=` is the follow-on completion —
        // `block` itself is never offered back.
        myFixture.configureByText("cur.ddot", "!!block<caret>");
        myFixture.complete(CompletionType.BASIC);
        myFixture.checkResult("!!block?end=");
    }

    public void testEndParamCompletesFromPartialQuery() {
        myFixture.configureByText("cur.ddot", "!!block?e<caret>");
        myFixture.complete(CompletionType.BASIC);
        myFixture.checkResult("!!block?end=");
    }

    public void testEndMarkerValueIsNeverCompleted() {
        String text = "!!block?end=DONE<caret>";
        myFixture.configureByText("cur.ddot", text);
        myFixture.complete(CompletionType.BASIC);
        // Nothing to offer: the marker is an arbitrary author-chosen string.
        List<String> shown = myFixture.getLookupElementStrings();
        assertTrue("marker value must not be completed: " + shown,
                shown == null || shown.isEmpty());
        myFixture.checkResult("!!block?end=DONE");
    }

    public void testCorpusHarvestedCommandNamesAreOffered() {
        // A command becomes completable simply by being used in the corpus.
        myFixture.addFileToProject("kb.ddot", "x .... !!mycmd\n");
        List<String> shown = completeIn("!!my<caret>");
        // Single match may auto-insert; accept either observable outcome.
        if (shown == null) {
            myFixture.checkResult("!!mycmd");
        } else {
            assertTrue(shown.toString(), shown.contains("!!mycmd"));
        }
    }

    /** Asserts all names are present and appear in the given relative order. */
    private static void assertOrderedSubset(List<String> shown, String... names) {
        int last = -1;
        for (String name : names) {
            int at = shown.indexOf(name);
            assertTrue("missing " + name + " in " + shown, at >= 0);
            assertTrue("out of order at " + name + ": " + shown, at > last);
            last = at;
        }
    }
}
