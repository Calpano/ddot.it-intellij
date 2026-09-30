package com.calpano.ddot.formatter;

import it.ddot.core.event.DdotEvent;
import it.ddot.core.io.DdotParser;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

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
 * Which lines are continuations, and which subject they inherit, comes from the
 * spec parser ({@link DdotParser}): a line is expanded only when a triple starts
 * on it and the line opens with its separator. So metadata-block lines, which
 * describe the previous triple, {@code ddot.it/off} regions, {@code !!block}
 * bodies, prose and blank lines are all left verbatim.
 */
public final class DdotToFullTriples {
    private DdotToFullTriples() {}

    public static @NotNull String expandDocument(@NotNull String text) {
        // 1-based line → subject of the triple starting there; null when several triples disagree.
        Map<Integer, String> subjectByLine = new HashMap<>();
        for (DdotEvent e : DdotParser.parse(text, "ddot", "input.ddot")) {
            subjectByLine.merge(e.startLineInResource(), e.from, (a, b) -> a.equals(b) ? a : null);
        }

        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            String line = lines[i];
            String subject = subjectByLine.get(i + 1);
            String trimmed = line.strip();
            if (subject != null && !subject.isEmpty() && !subject.contains("\n") && trimmed.startsWith("..")) {
                out.append(subject).append(' ').append(trimmed);
            } else {
                out.append(line);
            }
        }
        return out.toString();
    }
}
