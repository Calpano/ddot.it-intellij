package com.calpano.ddot.index;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls embedded ddot.it source out of the file types that can host it, from
 * raw text (no PSI) so it is usable from a {@code FileBasedIndex} indexer:
 *
 * <ul>
 *   <li>{@code .ddot} — the whole file is one chunk;</li>
 *   <li>Markdown — fenced blocks whose info string is {@code ddot} / {@code ddot.it}
 *       / {@code ddotit} (mirrors {@code DdotMarkdownFenceProvider});</li>
 *   <li>AsciiDoc — {@code [source,ddot]} / {@code [ddot]} listing blocks (mirrors the
 *       {@code HEADER}/{@code DELIM} detection in {@code DdotAsciiDocInjector}).</li>
 * </ul>
 *
 * <p>Each returned chunk is the inner ddot text, ready for
 * {@link com.calpano.ddot.it.token.DdotTokenizer#tokenize(String)} or
 * {@link com.calpano.ddot.it.event.DdotEventExporter#parse}.
 */
public final class DdotEmbeds {

    private DdotEmbeds() {}

    /**
     * One embedded ddot chunk. {@code startsOff} is {@code true} when the chunk
     * begins inside an `!!off` … `!!on` exclusion: an off/on directive written in
     * the HOST text (typically a host-language comment, e.g. AsciiDoc's
     * `+// https://ddot.it/off+`) — or left open by a previous chunk — excludes
     * the chunk's content until a matching `on`. Consumers pass the flag to
     * {@link com.calpano.ddot.it.token.DdotTokenizer#tokenize(String, boolean)}
     * or {@link com.calpano.ddot.it.event.DdotEventExporter#parse(String, String, String, boolean)}.
     */
    public record Chunk(@NotNull String text, boolean startsOff) {}

    /** Fence info-string / listing-label aliases accepted as ddot.it. */
    private static final Set<String> ALIASES = Set.of("ddot", "ddot.it", "ddotit");

    /**
     * A pre-parse off/on directive in host text, in any of the four command
     * spellings, with the name pinned (`!!office` is not `!!off`) and the `!!`
     * spelling held to EM2 (exactly two `!`, so `!!!off` is plain text).
     */
    private static final Pattern HOST_DIRECTIVE = Pattern.compile(
            "(?:(?:https?://)?ddot\\.it/|(?<!!)!!(?!!))(off|on)(?![^\\s?#])");

    private static final Set<String> DDOT_EXT = Set.of("ddot");
    private static final Set<String> MD_EXT = Set.of("md", "markdown", "mdown", "mkd", "mkdn");
    private static final Set<String> ADOC_EXT = Set.of("adoc", "asciidoc", "asc", "ad");

    // Markdown fence opener: optional indent, 3+ backticks or tildes, then the
    // info string whose first token is the language.
    private static final Pattern MD_FENCE = Pattern.compile("^\\s*([`~]{3,})\\s*(\\S+).*$");

    // AsciiDoc block header `[source,ddot]` / `[ddot]`, and a listing delimiter line.
    private static final Pattern ADOC_HEADER =
            Pattern.compile("^\\[\\s*(?:source\\s*,\\s*)?([A-Za-z][A-Za-z0-9._-]*)\\s*]\\s*$");
    private static final Pattern ADOC_DELIM = Pattern.compile("^([-=.~_*+])\\1{3,}\\s*$");

    /** True when {@code extension} is one of the ddot-hosting file types. */
    public static boolean isSupportedExtension(@Nullable String extension) {
        if (extension == null) return false;
        String e = extension.toLowerCase(Locale.ROOT);
        return DDOT_EXT.contains(e) || MD_EXT.contains(e) || ADOC_EXT.contains(e);
    }

    /**
     * Extract every embedded ddot chunk from {@code text} for the given file
     * extension, ignoring the off/on exclusion state. Prefer
     * {@link #extractChunks}: this text-only view exists for callers that handle
     * exclusion themselves (or knowingly ignore it).
     */
    public static @NotNull List<String> extract(@Nullable String extension, @NotNull CharSequence text) {
        List<Chunk> chunks = extractChunks(extension, text);
        List<String> out = new ArrayList<>(chunks.size());
        for (Chunk c : chunks) out.add(c.text());
        return out;
    }

    /**
     * Extract every embedded ddot chunk with its initial exclusion state. Host
     * text between chunks is scanned for `!!off` / `!!on` directives (Parse Spec,
     * pre-parse recognition: they are recognised anywhere on a line — that is
     * what lets a host comment carry them), and the off state threads THROUGH
     * chunks: an `!!off` inside one chunk with no matching `!!on` keeps the next
     * chunk off as well.
     */
    public static @NotNull List<Chunk> extractChunks(@Nullable String extension, @NotNull CharSequence text) {
        if (extension == null) return List.of();
        String e = extension.toLowerCase(Locale.ROOT);
        if (DDOT_EXT.contains(e)) return List.of(new Chunk(text.toString(), false));
        if (MD_EXT.contains(e)) return markdownFences(text.toString());
        if (ADOC_EXT.contains(e)) return asciidocListings(text.toString());
        return List.of();
    }

    /** Fold one host-text line into the running off state (last directive wins). */
    private static boolean hostState(String line, boolean off) {
        Matcher m = HOST_DIRECTIVE.matcher(line);
        while (m.find()) off = "off".equals(m.group(1));
        return off;
    }

    /**
     * The off state after a chunk parsed with {@code startOff}, read from the
     * canonical tokenizer so `!!off` inside a `!!block` body (verbatim, inert)
     * does not count, while an `!!on` inside an excluded span does.
     */
    private static boolean chunkEndState(String chunk, boolean startOff) {
        boolean off = startOff;
        for (com.calpano.ddot.it.token.DdotTokenizer.Token t
                : com.calpano.ddot.it.token.DdotTokenizer.tokenize(chunk, startOff)) {
            if (!"command".equals(t.token())) continue;
            if (com.calpano.ddot.it.token.DdotTokenizer.isOffCommand(t.text())) off = true;
            else if (com.calpano.ddot.it.token.DdotTokenizer.isOnCommand(t.text())) off = false;
        }
        return off;
    }

    private static List<Chunk> markdownFences(String text) {
        List<Chunk> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        boolean off = false;
        int i = 0;
        while (i < lines.length) {
            Matcher m = MD_FENCE.matcher(lines[i]);
            if (m.matches() && ALIASES.contains(m.group(2).toLowerCase(Locale.ROOT))) {
                char fenceChar = m.group(1).charAt(0);
                int fenceLen = m.group(1).length();
                StringBuilder sb = new StringBuilder();
                int j = i + 1;
                boolean first = true;
                while (j < lines.length && !isFenceClose(lines[j], fenceChar, fenceLen)) {
                    if (!first) sb.append('\n');
                    sb.append(lines[j]);
                    first = false;
                    j++;
                }
                String body = sb.toString();
                out.add(new Chunk(body, off));
                off = chunkEndState(body, off);
                i = j + 1; // skip past the closing fence (or EOF)
            } else {
                off = hostState(lines[i], off);
                i++;
            }
        }
        return out;
    }

    private static boolean isFenceClose(String line, char fenceChar, int openLen) {
        String t = line.strip();
        return t.length() >= openLen && allSame(t, fenceChar);
    }

    private static List<Chunk> asciidocListings(String text) {
        List<Chunk> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        boolean off = false;
        int i = 0;
        while (i < lines.length) {
            Matcher hm = ADOC_HEADER.matcher(lines[i].stripTrailing());
            if (hm.matches() && ALIASES.contains(hm.group(1).toLowerCase(Locale.ROOT))) {
                // The opening delimiter follows the header (possibly after a blank-free
                // run of attribute lines); bail on a blank line, which ends the block.
                int open = -1;
                char delimChar = 0;
                int delimLen = 0;
                for (int j = i + 1; j < lines.length; j++) {
                    String t = lines[j].stripTrailing();
                    if (t.isEmpty()) break;
                    Matcher dm = ADOC_DELIM.matcher(t);
                    if (dm.matches()) {
                        open = j;
                        delimChar = t.charAt(0);
                        delimLen = t.length();
                        break;
                    }
                }
                if (open >= 0) {
                    StringBuilder sb = new StringBuilder();
                    int k = open + 1;
                    boolean first = true;
                    int close = -1;
                    for (; k < lines.length; k++) {
                        String t = lines[k].stripTrailing();
                        if (t.length() >= delimLen && allSame(t, delimChar)) {
                            close = k;
                            break;
                        }
                        if (!first) sb.append('\n');
                        sb.append(lines[k]);
                        first = false;
                    }
                    if (close >= 0) {
                        String body = sb.toString();
                        out.add(new Chunk(body, off));
                        off = chunkEndState(body, off);
                        i = close + 1;
                        continue;
                    }
                }
            }
            off = hostState(lines[i], off);
            i++;
        }
        return out;
    }

    private static boolean allSame(String s, char c) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) != c) return false;
        return true;
    }
}
