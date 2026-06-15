package com.calpano.ddot.formatter;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expand continuation lines into self-contained triples by prepending the
 * inherited subject. Input:
 * <pre>
 *   s ..p1.. o1
 *   ..p2.. o2
 * </pre>
 * Output:
 * <pre>
 *   s ..p1.. o1
 *   s ..p2.. o2
 * </pre>
 * Metadata-block lines (between a trailing-{@code ,,} opener and a bare
 * {@code ,,} closer) are left untouched — they describe the previous event,
 * not new triples. {@code ddot.it/off}/{@code on} regions, blank lines, and
 * unparseable lines are preserved verbatim.
 */
public final class DdotToFullTriples {
    private DdotToFullTriples() {}

    private static final Pattern SEP = Pattern.compile("\\.{4}|\\.{2}");

    private static final String OFF_DIR       = "ddot.it/off";
    private static final String ON_DIR        = "ddot.it/on";
    private static final String OFF_DIR_SHORT = "!!off";
    private static final String ON_DIR_SHORT  = "!!on";

    public static @NotNull String expandDocument(@NotNull String text) {
        StringBuilder out = new StringBuilder(text.length());
        int len = text.length();
        int i = 0;
        boolean first = true;
        String currentSubject = null;
        boolean inMetaBlock = false;
        boolean off = false;
        while (i <= len) {
            int nl = text.indexOf('\n', i);
            int end = nl < 0 ? len : nl;
            String line = text.substring(i, end);
            if (!first) out.append('\n');

            String trimmed = line.trim();
            String emitted = line;

            if (trimmed.isEmpty()) {
                // pass through
            } else if (isOff(trimmed)) {
                off = true;
                inMetaBlock = false;
            } else if (isOn(trimmed)) {
                off = false;
            } else if (off) {
                // pass through
            } else if (inMetaBlock) {
                if (trimmed.equals(",,")) inMetaBlock = false;
                // pass through — block lines belong to the prior event
            } else {
                if (isContinuation(trimmed) && currentSubject != null) {
                    emitted = currentSubject + " " + trimmed;
                } else {
                    String newSubject = extractSubject(trimmed);
                    if (newSubject != null) currentSubject = newSubject;
                }
                if (opensMetaBlock(trimmed)) inMetaBlock = true;
            }

            out.append(emitted);
            first = false;
            if (nl < 0) break;
            i = nl + 1;
        }
        return out.toString();
    }

    private static boolean isOff(String s) { return OFF_DIR.equals(s) || OFF_DIR_SHORT.equals(s); }
    private static boolean isOn(String s)  { return ON_DIR.equals(s)  || ON_DIR_SHORT.equals(s);  }

    /** True iff the (non-metadata) line starts with a separator — empty subject slot. */
    private static boolean isContinuation(@NotNull String trimmed) {
        String head = headBeforeMeta(trimmed);
        Matcher m = SEP.matcher(head);
        if (!m.find()) return false;
        return head.substring(0, m.start()).trim().isEmpty();
    }

    /** Subject slot of the line, or null if no separator or empty subject. */
    private static @Nullable String extractSubject(@NotNull String trimmed) {
        String head = headBeforeMeta(trimmed);
        Matcher m = SEP.matcher(head);
        if (!m.find()) return null;
        String before = head.substring(0, m.start()).trim();
        return before.isEmpty() ? null : before;
    }

    /** Portion of the line before the first inline {@code ,,} (the triple body). */
    private static String headBeforeMeta(@NotNull String trimmed) {
        int idx = trimmed.indexOf(",,");
        return idx >= 0 ? trimmed.substring(0, idx) : trimmed;
    }

    private static boolean opensMetaBlock(@NotNull String trimmed) {
        return trimmed.endsWith(",,") && !trimmed.equals(",,");
    }
}
