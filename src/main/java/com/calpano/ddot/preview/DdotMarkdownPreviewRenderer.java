package com.calpano.ddot.preview;

import com.calpano.ddot.highlighting.DdotSyntaxHighlighter;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.TextAttributes;
import org.intellij.markdown.ast.ASTNode;
import org.intellij.plugins.markdown.extensions.CodeFenceGeneratingProvider;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown preview HTML for {@code ```ddot} fences. The Markdown plugin's
 * default fence renderer (lexer-based) misses the role-aware coloring our
 * {@link com.calpano.ddot.highlighting.DdotHighlightAnnotator} adds in the
 * editor — subjects, predicates, objects, metadata, commands. This renderer
 * reproduces that role assignment line-by-line and emits classed spans, so the
 * preview matches what users see while editing.
 *
 * <p>Pulls foreground colors from the active editor color scheme so the
 * preview tracks Light / Darcula / custom themes without hard-coded hexes.
 */
public final class DdotMarkdownPreviewRenderer implements CodeFenceGeneratingProvider {

    private static final Set<String> ALIASES = Set.of("ddot", "ddot.it", "ddotit");
    private static final Pattern SEP = Pattern.compile("\\.{4}|\\.{2}");
    private static final Set<String> COMMAND_TOKENS = Set.of("!!");
    // `!!` is shorthand for `ddot.it/`; both directive forms are equivalent.
    private static final String OFF       = "ddot.it/off";
    private static final String ON        = "ddot.it/on";
    private static final String OFF_SHORT = "!!off";
    private static final String ON_SHORT  = "!!on";

    private static boolean isOff(String s) { return OFF.equals(s) || OFF_SHORT.equals(s); }
    private static boolean isOn(String s)  { return ON.equals(s)  || ON_SHORT.equals(s);  }

    @Override
    public boolean isApplicable(@NotNull String language) {
        return ALIASES.contains(language.toLowerCase(Locale.ROOT));
    }

    @Override
    public @NotNull String generateHtml(@NotNull String language, @NotNull String raw, @NotNull ASTNode node) {
        // The Markdown plugin already wraps the result in <pre><code>…</code></pre>,
        // so we emit only the inner span-coloured content. Adding our own <pre>
        // produced a visible "block-in-block" nesting in the preview.
        StringBuilder out = new StringBuilder(raw.length() * 4);
        out.append(styleBlock());
        renderBody(raw, out);
        return out.toString();
    }

    /**
     * Body-only HTML — role-classed span markup, no {@code <style>} or
     * {@code <pre>} wrapper. Mirrors {@code DdotIt::Render.body} in
     * {@code ddot-render.rb}; the cross-implementation golden corpus at
     * {@code ../ddot.it/test-data/cases/} asserts byte equivalence.
     */
    public static String body(@NotNull String raw) {
        StringBuilder out = new StringBuilder(raw.length() * 4);
        renderBody(raw, out);
        return out.toString();
    }

    /** Per-line role assignment. Mirrors {@code DdotEventExporter}'s parsing. */
    private static void renderBody(String raw, StringBuilder out) {
        // Strip a single trailing newline that the Markdown lib often leaves on
        // fence bodies; preserve internal blank lines.
        String body = raw.endsWith("\n") ? raw.substring(0, raw.length() - 1) : raw;
        String[] lines = body.split("\n", -1);

        boolean inMetaBlock = false;
        boolean off = false;
        boolean firstLine = true;
        for (String line : lines) {
            if (!firstLine) out.append('\n');
            firstLine = false;

            String trimmed = line.trim();
            if (isOff(trimmed)) {
                renderInactiveLine(line, out, /*directive=*/true);
                off = true;
                continue;
            }
            if (isOn(trimmed)) {
                renderInactiveLine(line, out, /*directive=*/true);
                off = false;
                continue;
            }
            if (off) {
                renderInactiveLine(line, out, /*directive=*/false);
                continue;
            }

            if (trimmed.equals(",,")) {
                renderInline(line, out, "ddot-meta-sep");
                inMetaBlock = !inMetaBlock;
                continue;
            }

            if (inMetaBlock) {
                renderMetaContinuation(line, out);
                continue;
            }

            renderTripleLine(line, out);
        }
    }

    private static void renderTripleLine(String line, StringBuilder out) {
        // Split off ,, metadata tail (inline metadata).
        int metaStart = line.indexOf(",,");
        String body = metaStart >= 0 ? line.substring(0, metaStart) : line;
        String tail = metaStart >= 0 ? line.substring(metaStart) : "";

        // Extract leading whitespace verbatim.
        int leadEnd = 0;
        while (leadEnd < body.length() && Character.isWhitespace(body.charAt(leadEnd))) leadEnd++;
        out.append(escape(body.substring(0, leadEnd)));
        String content = body.substring(leadEnd);

        // Walk separator-delimited segments and assign roles by slot count.
        // Slot starts at 0 (subject); the leading separator on a continuation
        // line will advance it to 1, naturally classing the next segment as
        // the predicate. Don't pre-set slot=1 here — that double-counted the
        // leading `..` and mis-classed continuation predicates as objects.
        Matcher m = SEP.matcher(content);
        int last = 0;
        int slot = 0;

        boolean any = false;
        while (m.find()) {
            any = true;
            String segment = content.substring(last, m.start());
            renderSegment(segment, slot, out);
            String sep = m.group();
            // `....` advances 2 slots, `..` advances 1.
            int advance = sep.length() == 4 ? 2 : 1;
            out.append("<span class=\"ddot-sep\">").append(escape(sep)).append("</span>");
            slot = Math.min(slot + advance, 2);
            last = m.end();
        }
        // Trailing segment (object, or whole line if no separators).
        String trailing = content.substring(last);
        if (any) {
            renderSegment(trailing, slot, out);
        } else {
            // Free-form text line — likely a title or section header.
            renderFreeForm(trailing, out);
        }

        // Trailing inline-metadata block: `,, ..key.. value` etc.
        if (!tail.isEmpty()) renderMetaTail(tail, out);
    }

    private static void renderSegment(String segment, int slot, StringBuilder out) {
        // Preserve surrounding whitespace verbatim, color the trimmed content.
        int s = 0, e = segment.length();
        while (s < e && Character.isWhitespace(segment.charAt(s))) s++;
        while (e > s && Character.isWhitespace(segment.charAt(e - 1))) e--;
        if (s > 0) out.append(escape(segment.substring(0, s)));
        String text = segment.substring(s, e);
        if (!text.isEmpty()) {
            String cls = roleClass(slot, text);
            out.append("<span class=\"").append(cls).append("\">").append(escape(text)).append("</span>");
        }
        if (e < segment.length()) out.append(escape(segment.substring(e)));
    }

    private static String roleClass(int slot, String text) {
        if (text.startsWith("ddot.it") || COMMAND_TOKENS.contains(text)) return "ddot-command";
        return switch (Math.min(slot, 2)) {
            case 0 -> "ddot-subject";
            case 1 -> "ddot-predicate";
            default -> "ddot-object";
        };
    }

    private static void renderFreeForm(String text, StringBuilder out) {
        // ddot.it/this and similar still light up as commands even on free-form lines.
        String trimmed = text.trim();
        if (trimmed.startsWith("ddot.it") || COMMAND_TOKENS.contains(trimmed)) {
            int s = 0;
            while (s < text.length() && Character.isWhitespace(text.charAt(s))) s++;
            int e = text.length();
            while (e > s && Character.isWhitespace(text.charAt(e - 1))) e--;
            if (s > 0) out.append(escape(text.substring(0, s)));
            out.append("<span class=\"ddot-command\">").append(escape(text.substring(s, e))).append("</span>");
            if (e < text.length()) out.append(escape(text.substring(e)));
            return;
        }
        out.append(escape(text));
    }

    /** Render the trailing {@code ,, ...} inline-metadata segment as one meta block. */
    private static void renderMetaTail(String tail, StringBuilder out) {
        out.append("<span class=\"ddot-meta\">").append(escape(tail)).append("</span>");
    }

    /** Render a continuation line inside an open multi-line meta block. */
    private static void renderMetaContinuation(String line, StringBuilder out) {
        out.append("<span class=\"ddot-meta\">").append(escape(line)).append("</span>");
    }

    private static void renderInline(String line, StringBuilder out, String cls) {
        out.append("<span class=\"").append(cls).append("\">").append(escape(line)).append("</span>");
    }

    private static void renderInactiveLine(String line, StringBuilder out, boolean directive) {
        String cls = directive ? "ddot-directive" : "ddot-inactive";
        out.append("<span class=\"").append(cls).append("\">").append(escape(line)).append("</span>");
    }

    private static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '&' -> b.append("&amp;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    private static String styleBlock() {
        EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
        return "<style>"
                + ".ddot-subject{color:" + hex(scheme, DdotSyntaxHighlighter.SUBJECT, "#871094") + ";}"
                + ".ddot-predicate{color:" + hex(scheme, DdotSyntaxHighlighter.PREDICATE, "#1750EB") + ";}"
                + ".ddot-object{color:" + hex(scheme, DdotSyntaxHighlighter.OBJECT, "#067D17") + ";}"
                + ".ddot-meta,.ddot-meta-sep{color:" + hex(scheme, DdotSyntaxHighlighter.METADATA, "#8C8C8C") + ";font-style:italic;}"
                + ".ddot-command{color:" + hex(scheme, DdotSyntaxHighlighter.COMMAND, "#871094") + ";font-weight:bold;}"
                + ".ddot-sep{color:" + hex(scheme, DdotSyntaxHighlighter.SEPARATOR, "#707070") + ";}"
                + ".ddot-inactive,.ddot-directive{color:#8c8c8c;font-style:italic;opacity:0.7;}"
                + "</style>";
    }

    private static String hex(EditorColorsScheme scheme, TextAttributesKey key, String fallback) {
        TextAttributes attrs = scheme.getAttributes(key);
        Color c = attrs == null ? null : attrs.getForegroundColor();
        if (c == null) return fallback;
        return String.format("#%06x", c.getRGB() & 0xFFFFFF);
    }
}
