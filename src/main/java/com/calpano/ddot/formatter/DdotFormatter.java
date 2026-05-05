package com.calpano.ddot.formatter;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure string-based ddot.it formatter — direct port of the VS Code reference
 * implementation. Canonical line shapes:
 * <ul>
 *   <li>Typed link: {@code subject ..predicate.. object}</li>
 *   <li>Typed continuation: {@code ..predicate.. object}</li>
 *   <li>Simple link: {@code subject .... object}</li>
 *   <li>Simple continuation: {@code .... object}</li>
 * </ul>
 * Metadata {@code ,,} splits a line into independently-formatted parts joined
 * by {@code  ,, }.
 */
public final class DdotFormatter {
    private DdotFormatter() {}

    private static final Pattern SEP = Pattern.compile("\\.{4}|\\.{2}");

    public static @NotNull String formatDocument(@NotNull String text) {
        StringBuilder out = new StringBuilder(text.length());
        int len = text.length();
        int i = 0;
        boolean first = true;
        while (i <= len) {
            int nl = text.indexOf('\n', i);
            int end = nl < 0 ? len : nl;
            String line = text.substring(i, end);
            if (!first) out.append('\n');
            out.append(formatLine(line));
            first = false;
            if (nl < 0) break;
            i = nl + 1;
        }
        return out.toString();
    }

    static @NotNull String formatLine(@NotNull String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return trimRight(line);
        if (trimmed.equals(",,")) return ",,";

        // Each `,,`-separated part is formatted independently. ` ,, ` joins them; a
        // trailing empty part becomes ` ,,` (line ends in `,,` then newline).
        String[] parts = trimmed.split(",,", -1);
        StringBuilder acc = new StringBuilder();
        for (int idx = 0; idx < parts.length; idx++) {
            String p = formatPart(parts[idx]);
            if (idx == 0) {
                acc.append(p);
            } else if (p.isEmpty()) {
                acc.append(" ,,");
            } else {
                acc.append(" ,, ").append(p);
            }
        }
        return acc.toString().trim();
    }

    static @NotNull String formatPart(@NotNull String text) {
        List<String> segments = new ArrayList<>();
        List<String> seps = new ArrayList<>();
        Matcher m = SEP.matcher(text);
        int last = 0;
        while (m.find()) {
            segments.add(text.substring(last, m.start()).trim());
            seps.add(m.group());
            last = m.end();
        }
        segments.add(text.substring(last).trim());

        if (seps.isEmpty()) return segments.get(0);

        // subject .. predicate .. object
        if (seps.size() == 2 && seps.get(0).equals("..") && seps.get(1).equals("..")) {
            String s = segments.get(0), p = segments.get(1), o = segments.get(2);
            String head = s.isEmpty() ? "..%s..".formatted(p) : "%s ..%s..".formatted(s, p);
            return o.isEmpty() ? head : head + " " + o;
        }
        // subject .... object
        if (seps.size() == 1 && seps.get(0).equals("....")) {
            String s = segments.get(0), o = segments.get(1);
            String head = s.isEmpty() ? "...." : s + " ....";
            return o.isEmpty() ? head : head + " " + o;
        }

        // Fallback: rebuild verbatim with trimmed segments — leave malformed
        // lines alone rather than guess.
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            out.append(segments.get(i));
            if (i < seps.size()) out.append(seps.get(i));
        }
        return out.toString();
    }

    private static String trimRight(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == ' ' || s.charAt(end - 1) == '\t' || s.charAt(end - 1) == '\r')) end--;
        return s.substring(0, end);
    }
}
