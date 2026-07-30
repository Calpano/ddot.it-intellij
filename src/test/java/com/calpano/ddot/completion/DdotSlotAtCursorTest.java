package com.calpano.ddot.completion;

import com.calpano.ddot.completion.DdotContext.DocState;
import com.calpano.ddot.completion.DdotContext.State;
import com.calpano.ddot.it.token.DdotRole;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Table-driven test of the parse-automaton slot detection (Autocomplete
 * Specification, "Knowing which slot the cursor is in"): feed the automaton the
 * text before the cursor, starting in the state the document is in at the start
 * of the caret line, and read the slot off the state it reaches.
 */
public final class DdotSlotAtCursorTest {

    private static void assertState(State expected, String beforeCursor) {
        assertState(expected, beforeCursor, DocState.NORMAL);
    }

    private static void assertState(State expected, String beforeCursor, DocState docState) {
        assertEquals("state for <" + beforeCursor + "> in " + docState,
                expected, DdotContext.stateAtCursor(beforeCursor, docState));
    }

    private static void assertRole(DdotRole expected, String beforeCursor) {
        assertEquals("role for <" + beforeCursor + ">",
                expected, DdotContext.slotAtCursor(beforeCursor));
    }

    // --- part 1: the triple ------------------------------------------------

    @Test
    public void startOfLineAndSubject() {
        assertState(State.START_OF_LINE, "");
        assertState(State.START_OF_LINE, "   ");
        assertState(State.SUBJECT, "Dir");
        assertState(State.SUBJECT, "Alice Smith ");
        // EM2 (`!!`) groups with TX: a command prefix is still subject text.
        assertState(State.SUBJECT, "!!bl");
        // SC2 at line start is subject text too.
        assertState(State.SUBJECT, ";; x");
    }

    @Test
    public void cm2MidSubjectIsContent() {
        // parse.adoc: Subject --> Subject : WS | CM2 | SC2 | EM2 | TX
        assertState(State.SUBJECT, "Ac,,me ");
        assertRole(DdotRole.SUBJECT, "Ac,,me ");
    }

    @Test
    public void maybeBlockOpen() {
        // A line-initial `,,` may still be subject text: query `s`.
        assertState(State.MAYBE_BLOCK_OPEN, ",,");
        assertState(State.MAYBE_BLOCK_OPEN, ",, ");
        assertRole(DdotRole.SUBJECT, ",,");
        // The `,,` turned out to be subject text…
        assertState(State.SUBJECT, ",,x");
        assertState(State.SUBJECT, ",, more");
        // …or the subject `,,` is done and the relation starts.
        assertState(State.RELATION_START, ",, ..");
        assertState(State.OBJECT_START, ",, ....");
    }

    @Test
    public void relationStates() {
        assertState(State.RELATION_START, "a ..");
        assertState(State.RELATION_START, "a .. ");
        assertState(State.RELATION, "a ..w");
        assertState(State.RELATION, "a ..works at");
        // CM2 mid-relation is content.
        assertState(State.RELATION, "a ..w,,x");
        assertRole(DdotRole.PREDICATE, "a ..");
        assertRole(DdotRole.PREDICATE, "a ..w");
        // DT4 after a non-empty relation cannot be a triple.
        assertState(State.NOT_A_TRIPLE, "a ..w....");
    }

    @Test
    public void untypedFormIsAnEmptyRelationThenObject() {
        // `.. ..` is DT2 DT2: RelationStart + DT2 --> ObjectStart.
        assertState(State.OBJECT_START, "a .. .. ");
        assertState(State.OBJECT_START, "a ....");
        assertState(State.OBJECT_START, "a .... ");
        assertState(State.TRIPLE_OBJECT, "a .. .. b");
        assertState(State.TRIPLE_OBJECT, "a ....b");
        assertRole(DdotRole.OBJECT, "a .... ");
        // With the caret between the two dot pairs: an empty Relation slot.
        assertRole(DdotRole.PREDICATE, "a .. ");
    }

    @Test
    public void objectStates() {
        assertState(State.OBJECT_START, "a ..w.. ");
        assertState(State.TRIPLE_OBJECT, "a ..w.. o");
        // An object may contain `..` and `....`.
        assertState(State.TRIPLE_OBJECT, "a ..w.. o .. p");
        assertState(State.TRIPLE_OBJECT, "a ..links to.. ../../b.adoc");
        assertRole(DdotRole.OBJECT, "a ..w.. o");
        // Empty object followed by `,,` is not a triple (ObjectStart + CM2).
        assertState(State.NOT_A_TRIPLE, "a ..w.. ,,");
        assertRole(null, "a ..w.. ,,");
    }

    @Test
    public void strayDotRunsAreOrdinaryText() {
        // parse.adoc tokenization rule: a run of three (or five, …) dots is TX.
        assertState(State.SUBJECT, "...");
        assertState(State.SUBJECT, "a ... b");
        assertState(State.SUBJECT, "Mr. Smith");
        assertState(State.SUBJECT, "U.S.A.");
        assertState(State.SUBJECT, ".....");
        // Node.js-style names never count as separators.
        assertState(State.TRIPLE_OBJECT, "a ..uses.. Node.js");
    }

    // --- part 2: meta, inline form -----------------------------------------

    @Test
    public void metaInlineStates() {
        assertState(State.META_START, "a ..w.. o ,,");
        assertState(State.META_START, "a ..w.. o ,, ");
        assertRole(DdotRole.META_RELATION, "a ..w.. o ,,");
        assertState(State.META_RELATION_INLINE, "a ..w.. o ,, ..");
        assertState(State.META_RELATION_INLINE, "a ..w.. o ,, ..k");
        // CM2 mid-meta-relation is content.
        assertState(State.META_RELATION_INLINE, "a ..w.. o ,, ..k,,x");
        assertState(State.META_OBJECT_INLINE, "a ..w.. o ,, ..k.. ");
        assertState(State.META_OBJECT_INLINE, "a ..w.. o ,, ..k.. v");
        // CM2 mid-meta-object is content.
        assertState(State.META_OBJECT_INLINE, "a ..w.. o ,, ..k.. v,,w");
        assertRole(DdotRole.META_OBJECT, "a ..w.. o ,, ..k.. v");
        // The untyped meta form skips the meta relation.
        assertState(State.META_OBJECT_INLINE, "a ..w.. o ,, .... ");
    }

    @Test
    public void metaNextInline() {
        assertState(State.META_NEXT_INLINE, "a ..w.. o ,, ..k.. v ;;");
        assertState(State.META_NEXT_INLINE, "a ..w.. o ,, ..k.. v ;; ");
        assertRole(DdotRole.META_RELATION, "a ..w.. o ,, ..k.. v ;;");
        assertState(State.META_RELATION_INLINE, "a ..w.. o ,, ..k.. v ;; ..");
        assertState(State.META_OBJECT_INLINE, "a ..w.. o ,, ..k.. v ;; ....");
        // Text right after `;;` cannot become a pair.
        assertState(State.NOT_A_TRIPLE, "a ..w.. o ,, ..k.. v ;; x");
    }

    @Test
    public void metaTextIsProse() {
        assertState(State.META_TEXT_INLINE, "a ..w.. o ,, note");
        assertRole(null, "a ..w.. o ,, note");
        assertRole(null, "a ..w.. o ,, note .. still text");
    }

    // --- part 3: meta, block form ------------------------------------------

    @Test
    public void blockFirstLine() {
        assertState(State.BLOCK_FIRST_LINE, "", DocState.BLOCK_FIRST_LINE);
        assertEquals(DdotRole.META_RELATION, DdotContext.slotAtCursor("", DocState.BLOCK_FIRST_LINE));
        assertState(State.META_RELATION_IN_BLOCK, "..", DocState.BLOCK_FIRST_LINE);
        assertState(State.META_RELATION_IN_BLOCK, "..since", DocState.BLOCK_FIRST_LINE);
        assertState(State.META_OBJECT_IN_BLOCK, "..since.. ", DocState.BLOCK_FIRST_LINE);
        assertState(State.META_OBJECT_IN_BLOCK, "....", DocState.BLOCK_FIRST_LINE);
        // Text commits the block to prose.
        assertState(State.META_TEXT_BLOCK_LINE, "some text", DocState.BLOCK_FIRST_LINE);
        assertEquals(null, DdotContext.slotAtCursor("some text", DocState.BLOCK_FIRST_LINE));
        // A closing `,,` right away: empty block.
        assertState(State.BLOCK_END, ",,", DocState.BLOCK_FIRST_LINE);
    }

    @Test
    public void blockTripleLine() {
        assertState(State.BLOCK_TRIPLE_LINE, "", DocState.BLOCK_TRIPLE_LINE);
        assertEquals(DdotRole.META_RELATION, DdotContext.slotAtCursor("", DocState.BLOCK_TRIPLE_LINE));
        assertState(State.META_RELATION_IN_BLOCK, "..k", DocState.BLOCK_TRIPLE_LINE);
        assertState(State.META_OBJECT_IN_BLOCK, "..k.. v", DocState.BLOCK_TRIPLE_LINE);
        // `;;` inside a block meta object is content.
        assertState(State.META_OBJECT_IN_BLOCK, "..k.. v ;; w", DocState.BLOCK_TRIPLE_LINE);
        assertState(State.BLOCK_END, ",,", DocState.BLOCK_TRIPLE_LINE);
        // No text in a triple block.
        assertState(State.NOT_A_TRIPLE, "text", DocState.BLOCK_TRIPLE_LINE);
    }

    @Test
    public void blockTextLine() {
        assertState(State.META_TEXT_BLOCK_LINE, "more prose", DocState.BLOCK_TEXT_LINE);
        assertEquals(null, DdotContext.slotAtCursor("more prose", DocState.BLOCK_TEXT_LINE));
        assertState(State.BLOCK_END, ",,", DocState.BLOCK_TEXT_LINE);
        // No triples in a text block.
        assertState(State.NOT_A_TRIPLE, "..k", DocState.BLOCK_TEXT_LINE);
    }

    @Test
    public void inertRegionsHaveNoSlot() {
        assertState(State.INERT, "anything", DocState.EXCLUDED);
        assertState(State.INERT, "anything", DocState.VERBATIM);
        assertEquals(null, DdotContext.slotAtCursor("anything", DocState.EXCLUDED));
        assertEquals(null, DdotContext.slotAtCursor("", DocState.VERBATIM));
    }

    // --- document state at the start of the caret line ---------------------

    @Test
    public void documentStateOrdinaryLines() {
        assertEquals(DocState.NORMAL, DdotContext.documentStateAt(""));
        assertEquals(DocState.NORMAL, DdotContext.documentStateAt("a ..b.. c\n"));
        assertEquals(DocState.NORMAL, DdotContext.documentStateAt("prose line\n\n"));
        // Inline meta text does not open a block.
        assertEquals(DocState.NORMAL, DdotContext.documentStateAt("a ..b.. c ,, note\n"));
    }

    @Test
    public void documentStateExcluded() {
        assertEquals(DocState.EXCLUDED, DdotContext.documentStateAt("!!off\n"));
        assertEquals(DocState.EXCLUDED, DdotContext.documentStateAt("!!off\nsome stuff\n"));
        assertEquals(DocState.EXCLUDED, DdotContext.documentStateAt("ddot.it/off\n"));
        assertEquals(DocState.NORMAL, DdotContext.documentStateAt("!!off\n!!on\n"));
        // `!!office` is not `!!off`.
        assertEquals(DocState.NORMAL, DdotContext.documentStateAt("!!office\n"));
    }

    @Test
    public void documentStateVerbatim() {
        assertEquals(DocState.VERBATIM, DdotContext.documentStateAt("!!block\n"));
        assertEquals(DocState.VERBATIM, DdotContext.documentStateAt("!!block\nbody line\n"));
        // The marked form survives blank lines in the body…
        assertEquals(DocState.VERBATIM, DdotContext.documentStateAt("!!block?end=EOF\n\n"));
        // …the unmarked form ends at the first blank line.
        assertEquals(DocState.NORMAL, DdotContext.documentStateAt("!!block\nbody\n\n"));
        // A block opener as an object field also opens a verbatim body.
        assertEquals(DocState.VERBATIM, DdotContext.documentStateAt("a ..code.. !!block\n"));
    }

    @Test
    public void documentStateMetaBlock() {
        // A trailing `,,` on the triple line opens the block…
        assertEquals(DocState.BLOCK_FIRST_LINE, DdotContext.documentStateAt("a ..b.. c ,,\n"));
        // …as does a standalone `,,` on the line after a triple.
        assertEquals(DocState.BLOCK_FIRST_LINE, DdotContext.documentStateAt("a ..b.. c\n,,\n"));
        // A `,,` that follows no triple is NotATriple, not a block opener.
        assertEquals(DocState.NORMAL, DdotContext.documentStateAt(",,\n"));

        assertEquals(DocState.BLOCK_TRIPLE_LINE, DdotContext.documentStateAt("a ..b.. c ,,\n..k.. v\n"));
        assertEquals(DocState.BLOCK_TRIPLE_LINE, DdotContext.documentStateAt("a ..b.. c\n,,\n..k.. v\n"));
        assertEquals(DocState.BLOCK_TEXT_LINE, DdotContext.documentStateAt("a ..b.. c ,,\nsome prose\n"));
        // The closing `,,` ends the block.
        assertEquals(DocState.NORMAL, DdotContext.documentStateAt("a ..b.. c ,,\n..k.. v\n,,\n"));
    }
}
