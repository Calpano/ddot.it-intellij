package com.calpano.ddot.structure;

import com.intellij.ide.util.treeView.smartTree.TreeElement;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.SimpleTextAttributes;

/**
 * AsciiDoc section headings form the outline tree; triple subjects hang under
 * the section they appear in.
 */
public final class DdotStructureViewTest extends BasePlatformTestCase {

    public void testSectionsNestAndHoldSubjects() {
        PsiFile file = myFixture.configureByText("t.ddot", """
                Top ..is.. first
                = Doc
                Alice ..knows.. Bob
                == Chapter A
                Carol ..likes.. Tea
                Carol ..likes.. Coffee
                ==== Deep (skipped level)
                Dave ..owns.. Car
                == Chapter B ==
                ===
                ====
                Alice ..knows.. Eve
                = Second
                """);
        assertEquals("""
                Top
                [Doc]
                  Alice
                  [Chapter A]
                    Carol
                    [Deep (skipped level)]
                      Dave
                  [Chapter B]
                    Alice
                [Second]
                """, dump(file));
    }

    public void testHeadingsInOffRegionAreIgnored() {
        PsiFile file = myFixture.configureByText("t.ddot", """
                = Live
                ddot.it/off
                == Dead
                Ghost ..is.. hidden
                ddot.it/on
                Real ..is.. here
                """);
        assertEquals("""
                [Live]
                  Real
                """, dump(file));
    }

    private static String dump(PsiFile file) {
        DdotStructureViewModel model = new DdotStructureViewModel(file);
        try {
            StringBuilder sb = new StringBuilder();
            for (TreeElement child : model.getRoot().getChildren()) dump(child, 0, sb);
            return sb.toString();
        } finally {
            model.dispose();
        }
    }

    private static void dump(TreeElement e, int depth, StringBuilder sb) {
        String text = e.getPresentation().getPresentableText();
        if (e instanceof DdotSectionElement section) {
            var fragments = ((com.intellij.ide.projectView.PresentationData) section.getPresentation()).getColoredText();
            assertEquals(SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES, fragments.get(0).getAttributes());
            text = "[" + text + "]";
        }
        sb.append("  ".repeat(depth)).append(text).append('\n');
        for (TreeElement child : e.getChildren()) dump(child, depth + 1, sb);
    }
}
