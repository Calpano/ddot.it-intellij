package com.calpano.ddot.completion;

import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupEx;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

/**
 * Spec-conformance behaviours of the completion contributor beyond slot
 * detection: the untyped-form rewrite, the case-insensitive "typed text is not
 * offered back" rule, the deterministic total order, recency for meta
 * relations, and completion inside {@code ,,} blocks.
 */
public final class DdotCompletionBehaviorTest extends BasePlatformTestCase {

    // --- untyped-form rewrite ------------------------------------------------

    public void testCompletingRelationInsideSpacedUntypedForm() {
        myFixture.addFileToProject("kb.ddot", "Alice ..works at.. Acme\n");
        // `a .. .. b` with the caret between the dot pairs is an empty Relation
        // slot; completing must rewrite to the typed form, not duplicate `..`.
        myFixture.configureByText("cur.ddot", "a .. <caret>.. b");
        myFixture.complete(CompletionType.BASIC);
        List<String> shown = myFixture.getLookupElementStrings();
        assertNotNull(shown);
        assertTrue("the empty relation slot is offered the relation query: " + shown,
                shown.contains("..works at.."));
        accept("..works at..");
        myFixture.checkResult("a ..works at.. b");
    }

    public void testCompletingRelationInsideQuadDotForm() {
        myFixture.addFileToProject("kb.ddot", "Alice ..works at.. Acme\n");
        myFixture.configureByText("cur.ddot", "a ..<caret>.. b");
        myFixture.complete(CompletionType.BASIC);
        accept("..works at..");
        myFixture.checkResult("a ..works at.. b");
    }

    /**
     * Accepts the named lookup item, if the lookup is still open (the platform
     * auto-inserts a lone candidate, but recency entries from sibling tests can
     * keep the list longer than one).
     */
    private void accept(String lookupString) {
        LookupEx lookup = myFixture.getLookup();
        if (lookup == null) return;
        for (LookupElement element : lookup.getItems()) {
            if (element.getLookupString().equals(lookupString)) {
                lookup.setCurrentItem(element);
                break;
            }
        }
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);
    }

    public void testCompletingPartialRelationConsumesExistingClosingDots() {
        myFixture.addFileToProject("kb.ddot", "Alice ..works at.. Acme\n");
        myFixture.configureByText("cur.ddot", "a ..wo<caret>.. b");
        myFixture.complete(CompletionType.BASIC);
        myFixture.checkResult("a ..works at.. b");
    }

    public void testCompletingRelationWithoutFollowingDotsAddsClosing() {
        myFixture.addFileToProject("kb.ddot", "Alice ..works at.. Acme\n");
        myFixture.configureByText("cur.ddot", "a ..wo<caret>");
        myFixture.complete(CompletionType.BASIC);
        myFixture.checkResult("a ..works at.. ");
    }

    // --- typed text is not offered back (case-insensitive) --------------------

    public void testExactTypedTextNotOfferedBack() {
        myFixture.addFileToProject("kb.ddot", "Alice ..likes.. Tea\n");
        myFixture.configureByText("cur.ddot", "Bob ..likes.. Tea<caret>");
        myFixture.complete(CompletionType.BASIC);
        List<String> shown = myFixture.getLookupElementStrings();
        assertTrue("Tea must not be offered back: " + shown,
                shown == null || !shown.contains("Tea"));
        myFixture.checkResult("Bob ..likes.. Tea");
    }

    public void testTypedTextNotOfferedBackIgnoringCase() {
        myFixture.addFileToProject("kb.ddot", "Alice ..likes.. Tea\n");
        myFixture.configureByText("cur.ddot", "Bob ..likes.. tea<caret>");
        myFixture.complete(CompletionType.BASIC);
        List<String> shown = myFixture.getLookupElementStrings();
        // "tea" == "Tea" after case-insensitive comparison: dropped, and since it
        // was the only match, nothing may be auto-inserted either.
        assertTrue("case-insensitively equal candidate must be dropped: " + shown,
                shown == null || !shown.contains("Tea"));
        myFixture.checkResult("Bob ..likes.. tea");
    }

    // --- deterministic total order --------------------------------------------

    public void testTiedCandidatesOrderAlphabeticallyCaseInsensitive() {
        myFixture.addFileToProject("kb.ddot", "Alpha .... x\nbeta .... y\nGamma .... z\n");
        myFixture.configureByText("cur.ddot", "<caret>");
        myFixture.complete(CompletionType.BASIC);
        List<String> shown = myFixture.getLookupElementStrings();
        assertNotNull(shown);
        // Same pool, same frequency: alphabetical (key 7), case-insensitively,
        // subjects pool before objects pool (key 4).
        assertEquals(List.of("Alpha", "beta", "Gamma", "x", "y", "z"), shown);
    }

    public void testOrderIsStableAcrossInvocations() {
        myFixture.addFileToProject("kb.ddot", "Alpha .... x\nbeta .... y\nGamma .... z\n");
        myFixture.configureByText("a.ddot", "<caret>");
        myFixture.complete(CompletionType.BASIC);
        List<String> first = myFixture.getLookupElementStrings();
        assertNotNull(first);

        // A second, fresh invocation must produce the identical list — the order
        // is total and deterministic, immune to platform statistics weighers.
        myFixture.configureByText("b.ddot", "<caret>");
        myFixture.complete(CompletionType.BASIC);
        List<String> second = myFixture.getLookupElementStrings();
        assertEquals(first, second);
    }

    // --- explicit invocation always works --------------------------------------

    public void testStrayThreeDotsDoNotSuppressCompletion() {
        // The old STRAY_THREE_DOTS early-return hid the menu whenever the text
        // before the caret ended in `...` — wrongly suppressing even explicit
        // invocation. `...` is ordinary text; mid-typing a separator the `....`
        // snippet still applies and completes the run.
        myFixture.configureByText("cur.ddot", "...<caret>");
        myFixture.complete(CompletionType.BASIC);
        myFixture.checkResult("....");
    }

    public void testExplicitInvocationWorksInEmptySlotAfterThreeDotsText() {
        // A `...` earlier on the line (ordinary text) must not block completion
        // in a later, empty slot.
        myFixture.addFileToProject("kb.ddot", "Alice ..likes.. Tea\nAlfred ..likes.. Coffee\n");
        myFixture.configureByText("cur.ddot", "wait... ..likes.. <caret>");
        myFixture.complete(CompletionType.BASIC);
        List<String> shown = myFixture.getLookupElementStrings();
        assertNotNull("explicit invocation must always work in an empty slot", shown);
        assertTrue(shown.toString(), shown.contains("Tea"));
    }

    // --- recency for relation-like slots -----------------------------------------

    public void testMetaRelationInsertRecordsRecency() {
        myFixture.addFileToProject("kb.ddot", "s ..p.. o ,, ..kind.. v\n");
        myFixture.configureByText("cur.ddot", "x ..r.. y ,, ..ki<caret>");
        myFixture.complete(CompletionType.BASIC);
        myFixture.checkResult("x ..r.. y ,, ..kind.. ");
        assertTrue("meta relation insert must feed the recency list",
                DdotRecentRelations.getInstance(getProject()).recent(3).contains("kind"));
    }

    // --- completion inside `,,` blocks ---------------------------------------------

    public void testMetaRelationCompletesInsideBlockAfterDots() {
        myFixture.addFileToProject("kb.ddot", "s ..p.. o ,, ..since.. 2020\n");
        myFixture.configureByText("cur.ddot", "a ..b.. c ,,\n..si<caret>");
        myFixture.complete(CompletionType.BASIC);
        myFixture.checkResult("a ..b.. c ,,\n..since.. ");
    }

    public void testFreshLineInsideBlockOffersMetaRelations() {
        myFixture.addFileToProject("kb.ddot", "s ..p.. o ,, ..since.. 2020\n");
        myFixture.configureByText("cur.ddot", "a ..b.. c ,,\n<caret>");
        myFixture.complete(CompletionType.BASIC);
        List<String> shown = myFixture.getLookupElementStrings();
        assertNotNull("a fresh line inside a `,,` block is a MetaRelation slot", shown);
        assertTrue(shown.toString(), shown.contains("..since.."));
        // Own pool (mr) before the other relation pool (p).
        assertTrue(shown.toString(), shown.indexOf("..since..") < shown.indexOf("..p.."));
    }

    public void testMetaObjectCompletesInsideBlock() {
        myFixture.addFileToProject("kb.ddot", "s ..p.. o ,, ..since.. LastYear\n");
        myFixture.configureByText("cur.ddot", "a ..b.. c ,,\n..since.. Last<caret>");
        myFixture.complete(CompletionType.BASIC);
        myFixture.checkResult("a ..b.. c ,,\n..since.. LastYear");
    }
}
