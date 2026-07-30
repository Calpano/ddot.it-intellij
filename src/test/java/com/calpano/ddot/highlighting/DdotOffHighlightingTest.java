package com.calpano.ddot.highlighting;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.util.TextRange;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Locks down off/on highlighting: the {@code ddot.it/off} and {@code ddot.it/on}
 * directive tokens stay command-coloured, while every line between them is
 * dimmed uniformly — no subject/predicate/object/metadata role colour is layered
 * on inactive content (see {@link DdotHighlightAnnotator}).
 */
public final class DdotOffHighlightingTest extends BasePlatformTestCase {

    private static final String SOURCE = String.join("\n",
            "Active ..likes.. Coffee",   // line 0 — active triple
            "ddot.it/off",               // line 1 — directive (stays command)
            "Draft ..maybe.. ship it",   // line 2 — inactive content (dimmed)
            "ddot.it/on",                // line 3 — directive (stays command)
            "Back ..works.. now");       // line 4 — active again

    private Map<TextAttributesKey, List<TextRange>> rolesByKey() {
        myFixture.configureByText("sample.ddot", SOURCE);
        Map<TextAttributesKey, List<TextRange>> byKey = new HashMap<>();
        for (HighlightInfo info : myFixture.doHighlighting()) {
            TextAttributesKey key = info.forcedTextAttributesKey;
            if (key == null) continue;
            byKey.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new TextRange(info.getStartOffset(), info.getEndOffset()));
        }
        return byKey;
    }

    private static boolean covers(List<TextRange> ranges, int offset) {
        if (ranges == null) return false;
        for (TextRange r : ranges) if (r.containsOffset(offset)) return true;
        return false;
    }

    private static boolean intersectsLine(List<TextRange> ranges, int lineStart, int lineEnd) {
        if (ranges == null) return false;
        for (TextRange r : ranges) {
            if (r.getStartOffset() < lineEnd && r.getEndOffset() > lineStart) return true;
        }
        return false;
    }

    public void testDirectivesStayColoredAndContentIsDimmed() {
        Map<TextAttributesKey, List<TextRange>> byKey = rolesByKey();

        // Directive tokens keep their command colour.
        assertTrue("ddot.it/off should be command-coloured",
                covers(byKey.get(DdotSyntaxHighlighter.COMMAND), SOURCE.indexOf("ddot.it/off")));
        assertTrue("ddot.it/on should be command-coloured",
                covers(byKey.get(DdotSyntaxHighlighter.COMMAND), SOURCE.indexOf("ddot.it/on")));

        // The inactive content line carries the INACTIVE dim ...
        int contentStart = SOURCE.indexOf("Draft");
        int contentEnd = SOURCE.indexOf('\n', contentStart);
        assertTrue("inactive content line should be dimmed",
                intersectsLine(byKey.get(DdotSyntaxHighlighter.INACTIVE), contentStart, contentEnd));

        // ... but NO role colour is layered on it.
        for (TextAttributesKey role : List.of(DdotSyntaxHighlighter.SUBJECT,
                DdotSyntaxHighlighter.PREDICATE, DdotSyntaxHighlighter.OBJECT,
                DdotSyntaxHighlighter.METADATA)) {
            assertFalse("no " + role.getExternalName() + " on inactive content",
                    intersectsLine(byKey.get(role), contentStart, contentEnd));
        }
    }

    public void testActiveLinesStillGetRoleColors() {
        Map<TextAttributesKey, List<TextRange>> byKey = rolesByKey();
        // Sanity: highlighting still works outside the off span.
        assertTrue("active subject should be coloured",
                covers(byKey.get(DdotSyntaxHighlighter.SUBJECT), SOURCE.indexOf("Active")));
        assertTrue("active subject after the span should be coloured",
                covers(byKey.get(DdotSyntaxHighlighter.SUBJECT), SOURCE.indexOf("Back")));
    }
}
