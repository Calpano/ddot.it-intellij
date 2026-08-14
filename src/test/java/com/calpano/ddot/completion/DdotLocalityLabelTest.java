package com.calpano.ddot.completion;

import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The lookup's type text distinguishes candidates the current file already uses
 * ("Object in file") from ones only the rest of the project knows ("Object in
 * project"). The distinction has to track the same locality that
 * {@link com.calpano.ddot.index.DdotEntityIndex#namesInFile} feeds into ranking,
 * so the label explains why an entry sits where it does.
 */
public final class DdotLocalityLabelTest extends BasePlatformTestCase {

    /** Lookup string → type text, in the order the lookup shows them. */
    private Map<String, String> typeTexts() {
        LookupElement[] elements = myFixture.completeBasic();
        assertNotNull("a lookup was shown", elements);
        Map<String, String> out = new LinkedHashMap<>();
        for (LookupElement e : elements) {
            LookupElementPresentation p = new LookupElementPresentation();
            e.renderElement(p);
            out.putIfAbsent(e.getLookupString(), p.getTypeText());
        }
        return out;
    }

    public void testObjectsSplitByLocality() {
        myFixture.addFileToProject("kb.ddot", "Alice ..likes.. Tea\n");
        myFixture.configureByText("cur.ddot", "Bob ..likes.. Coffee\nCarol ..likes.. <caret>");

        Map<String, String> types = typeTexts();
        assertEquals("used in this file", "Object in file", types.get("Coffee"));
        assertEquals("only elsewhere in the project", "Object in project", types.get("Tea"));
    }

    /**
     * Locality is same-FILE, not same-file-and-same-role — matching the ranking
     * rule. A name written as a subject here is "in file" when offered as an
     * object.
     */
    public void testAnyRoleInTheFileCounts() {
        myFixture.addFileToProject("kb.ddot", "Zoe ..likes.. Tea\n");
        myFixture.configureByText("cur.ddot", "Coffee ..is a.. Drink\nCarol ..likes.. <caret>");

        Map<String, String> types = typeTexts();
        assertEquals("subject here, offered as object", "Object in file", types.get("Coffee"));
        assertEquals("Object in project", types.get("Tea"));
    }

    public void testSubjectSlotLabelsTheSameWay() {
        myFixture.addFileToProject("kb.ddot", "Zoe ..likes.. Tea\n");
        myFixture.configureByText("cur.ddot", "Coffee ..is a.. Drink\n<caret>");

        Map<String, String> types = typeTexts();
        assertEquals("Subject in file", types.get("Coffee"));
        assertEquals("Subject in project", types.get("Zoe"));
    }

    /**
     * The index lags unsaved edits, so the label must come from the PSI: a name
     * typed into this file and never saved still reads "in file".
     *
     * <p>Both candidates share the prefix on purpose. With a prefix that matches
     * exactly one, the platform inserts it outright and never shows a lookup to
     * inspect.
     */
    public void testUnsavedNameCountsAsInFile() {
        myFixture.addFileToProject("kb.ddot", "Alice ..likes.. Fernwood\n");
        myFixture.configureByText("cur.ddot", "Bob ..likes.. Fernet\nCarol ..likes.. Fer<caret>");

        Map<String, String> types = typeTexts();
        assertEquals("typed into this file, not yet indexed", "Object in file", types.get("Fernet"));
        assertEquals("Object in project", types.get("Fernwood"));
    }
}
