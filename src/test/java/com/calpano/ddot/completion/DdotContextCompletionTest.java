package com.calpano.ddot.completion;

import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

/**
 * End-to-end check of context-aware object completion: an object previously used
 * with the relation on the current line is ranked above objects that were not.
 * Exercises the whole realized path — per-file indexing → the project-wide
 * relation→objects join → the completion priority boost → lookup order.
 */
public final class DdotContextCompletionTest extends BasePlatformTestCase {

    public void testObjectsForCurrentRelationRankFirst() {
        // Project knowledge: "Tea" was linked with "likes"; "Car" only with "owns".
        myFixture.addFileToProject("kb.ddot", "Alice ..likes.. Tea\nBob ..owns.. Car\n");

        // Typing a new object after "..likes..".
        myFixture.configureByText("cur.ddot", "Carol ..likes.. <caret>");
        myFixture.complete(CompletionType.BASIC);
        List<String> shown = myFixture.getLookupElementStrings();

        assertNotNull("a lookup was shown", shown);
        assertTrue("both objects offered: " + shown, shown.contains("Tea") && shown.contains("Car"));
        assertTrue("object used with this relation ranks first: " + shown,
                shown.indexOf("Tea") < shown.indexOf("Car"));
    }

    public void testRangeInferredObjectsSurfaceAndRankFirst() {
        myFixture.addFileToProject("kb.ddot", String.join("\n",
                "Alice ..works at.. Acme",   // the only "works at" usage
                "Acme ..is a.. Company",
                "Globex ..is a.. Company",
                "Initech ..is a.. Company",
                "Carol ..is a.. Person", ""));

        myFixture.configureByText("cur.ddot", "Dave ..works at.. <caret>");
        myFixture.complete(CompletionType.BASIC);
        List<String> shown = myFixture.getLookupElementStrings();

        assertNotNull(shown);
        // Every Company is offered — including Globex/Initech, never linked by "works at".
        assertTrue("range objects offered: " + shown, shown.containsAll(List.of("Acme", "Globex", "Initech")));
        // A range-inferred object outranks a plain in-project object (the type node "Company").
        assertTrue("range object ranks above generic object: " + shown,
                shown.indexOf("Initech") < shown.indexOf("Company"));

        // A Person instance is NOT type-appropriate for "works at" — but that
        // demotes it, it does not hide it. The Autocomplete Specification's
        // query for an object slot is `o, s, mo, p, mr`, so an entity named as a
        // subject elsewhere is a legitimate object candidate; type inference is
        // a ranking signal, not a filter. Suppressing it would also make the
        // completion wrong whenever the inferred range is merely incomplete.
        assertTrue("Person instance still offered: " + shown, shown.contains("Carol"));
        assertTrue("type-appropriate object outranks a type-inappropriate one: " + shown,
                shown.indexOf("Initech") < shown.indexOf("Carol"));
    }
}
