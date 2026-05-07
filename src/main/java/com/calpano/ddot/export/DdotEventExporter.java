package com.calpano.ddot.export;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure parser → event-stream emitter. Direct port of the VS Code
 * {@code parseDocument} so the JSONL output is byte-identical to the VS Code
 * extension's {@code ddot.exportJson} command.
 * <p>The grammar is line-oriented:
 * <ul>
 *   <li>{@code subject ..type.. object} → typed event</li>
 *   <li>{@code subject .... object} → untyped event</li>
 *   <li>Continuation lines (no leading subject) inherit the previous subject.</li>
 *   <li>{@code ,, ..key.. value} on the same line → inline metadata.</li>
 *   <li>Bare {@code ,,} at end of line opens a multi-line metadata block,
 *       closed by another bare {@code ,,}.</li>
 * </ul>
 */
public final class DdotEventExporter {
    private DdotEventExporter() {}

    private static final Pattern SEP = Pattern.compile("\\.{4}|\\.{2}");

    // Both forms are equivalent: `!!` is shorthand for `ddot.it/`.
    private static final String OFF_DIR       = "ddot.it/off";
    private static final String ON_DIR        = "ddot.it/on";
    private static final String OFF_DIR_SHORT = "!!off";
    private static final String ON_DIR_SHORT  = "!!on";

    private static boolean isOff(String s) { return OFF_DIR.equals(s) || OFF_DIR_SHORT.equals(s); }
    private static boolean isOn(String s)  { return ON_DIR.equals(s)  || ON_DIR_SHORT.equals(s);  }

    public static @NotNull List<DdotEvent> parse(@NotNull String text,
                                                 @NotNull String kind,
                                                 @NotNull String source) {
        List<DdotEvent> events = new ArrayList<>();
        String[] lines = text.split("\n", -1);

        String currentSubject = null;
        DdotEvent openMetaEvent = null;
        boolean off = false;

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()) continue;

            if (isOff(trimmed)) {
                off = true;
                // An open multi-line meta block can't survive an off span; close it.
                openMetaEvent = null;
                continue;
            }
            if (isOn(trimmed)) {
                off = false;
                continue;
            }
            if (off) continue;

            if (openMetaEvent != null) {
                if (trimmed.equals(",,")) {
                    openMetaEvent = null;
                    continue;
                }
                Split sp = splitLine(trimmed);
                Triple t = extractTriple(sp.segments, sp.seps, currentSubject);
                if (t != null && t.type != null) {
                    openMetaEvent.meta.add(new DdotEvent.MetaPair(t.type, t.to));
                }
                continue;
            }

            Split sp = splitLine(trimmed);
            Triple t = extractTriple(sp.segments, sp.seps, currentSubject);
            if (t == null) continue;

            DdotEvent event = new DdotEvent(t.from, t.type, t.to, kind, source, i + 1);
            currentSubject = t.from;

            if (!sp.meta.isEmpty()) {
                Split mp = splitLine(sp.meta);
                Triple mt = extractTriple(mp.segments, mp.seps, currentSubject);
                if (mt != null && mt.type != null) {
                    event.meta.add(new DdotEvent.MetaPair(mt.type, mt.to));
                }
            } else if (trimmed.endsWith(",,")) {
                openMetaEvent = event;
            }

            events.add(event);
        }

        return events;
    }

    public static @NotNull String toJsonl(@NotNull List<DdotEvent> events) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) out.append('\n');
            out.append(toJson(events.get(i)));
        }
        return out.toString();
    }

    /**
     * Field order matches the spec: from, type?, to, meta?, kind, source, location.
     * Hand-written to keep the output deterministic and dependency-free.
     */
    private static String toJson(DdotEvent e) {
        StringBuilder b = new StringBuilder();
        b.append('{');
        appendField(b, "from", e.from, false);
        if (e.type != null) appendField(b, "type", e.type, true);
        appendField(b, "to", e.to, true);
        if (!e.meta.isEmpty()) {
            b.append(',').append('"').append("meta").append("\":[");
            for (int i = 0; i < e.meta.size(); i++) {
                if (i > 0) b.append(',');
                DdotEvent.MetaPair mp = e.meta.get(i);
                b.append('{');
                appendField(b, "type", mp.type(), false);
                appendField(b, "to", mp.to(), true);
                b.append('}');
            }
            b.append(']');
        }
        appendField(b, "kind", e.kind, true);
        appendField(b, "source", e.source, true);
        b.append(',').append('"').append("location").append("\":").append(e.location);
        b.append('}');
        return b.toString();
    }

    private static void appendField(StringBuilder b, String key, String value, boolean leadingComma) {
        if (leadingComma) b.append(',');
        b.append('"').append(key).append("\":\"").append(escapeJson(value)).append('"');
    }

    private static String escapeJson(String s) {
        StringBuilder out = new StringBuilder(s.length() + 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private record Split(@NotNull List<String> segments, @NotNull List<String> seps, @NotNull String meta) {}
    private record Triple(@NotNull String from, @Nullable String type, @NotNull String to) {}

    private static Split splitLine(String text) {
        int idx = text.indexOf(",,");
        String body = idx >= 0 ? text.substring(0, idx) : text;
        String meta = idx >= 0 ? text.substring(idx + 2).trim() : "";

        List<String> segments = new ArrayList<>();
        List<String> seps = new ArrayList<>();
        Matcher m = SEP.matcher(body);
        int last = 0;
        while (m.find()) {
            segments.add(body.substring(last, m.start()).trim());
            seps.add(m.group());
            last = m.end();
        }
        segments.add(body.substring(last).trim());
        return new Split(segments, seps, meta);
    }

    private static @Nullable Triple extractTriple(List<String> segments, List<String> seps,
                                                  @Nullable String inheritedSubject) {
        // Typed: from ..type.. to (or continuation: ..type.. to).
        // Empty type ⇒ `.. ..` form, a typographic variant of `....` (untyped).
        if (segments.size() == 3 && seps.size() == 2
                && seps.get(0).equals("..") && seps.get(1).equals("..")) {
            String s = segments.get(0), p = segments.get(1), o = segments.get(2);
            if (o.isEmpty()) return null;
            String from = !s.isEmpty() ? s : inheritedSubject;
            if (from == null) return null;
            if (p.isEmpty()) return new Triple(from, null, o);
            return new Triple(from, p, o);
        }
        // Simple: from .... to (or continuation: .... to)
        if (segments.size() == 2 && seps.size() == 1 && seps.get(0).equals("....")) {
            String s = segments.get(0), o = segments.get(1);
            if (o.isEmpty()) return null;
            String from = !s.isEmpty() ? s : inheritedSubject;
            if (from == null) return null;
            return new Triple(from, null, o);
        }
        return null;
    }
}
