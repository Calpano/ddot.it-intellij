package com.calpano.ddot.completion;

import com.intellij.openapi.editor.Document;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure helpers for figuring out whether the cursor sits in a "continuation context" —
 * i.e. there's a current subject in scope from an earlier line, so a fresh line
 * should auto-suggest predicates as {@code ..rel..}. Shared between the completion
 * contributor and the auto-popup listener.
 */
final class DdotContext {
    private DdotContext() {}

    private static final Pattern SEP = Pattern.compile("\\.{4}|\\.{2}");

    /** True when an earlier line (since the most recent blank line) has a complete triple. */
    static boolean isContinuationContext(Document doc, int line) {
        for (int i = line - 1; i >= 0; i--) {
            int s = doc.getLineStartOffset(i);
            int e = doc.getLineEndOffset(i);
            String text = doc.getCharsSequence().subSequence(s, e).toString().trim();
            if (text.isEmpty()) return false;
            int four = 0, two = 0;
            Matcher m = SEP.matcher(text);
            while (m.find()) {
                if (m.group().length() == 4) four++;
                else two++;
            }
            if (two >= 2 || four >= 1) return true;
        }
        return false;
    }
}
