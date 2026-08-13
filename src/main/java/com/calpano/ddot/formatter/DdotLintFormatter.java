package com.calpano.ddot.formatter;

import it.ddot.core.lint.DdotLinter;
import org.jetbrains.annotations.NotNull;


/**
 * The plugin's one and only ddot.it pretty-printer: a thin adapter over the <em>specified</em>
 * formatter, {@link DdotLinter}'s FIX path (rules DL001–DL009) in the shared
 * {@code com.calpano.ddot.it:ddot-core} library.
 *
 * <p>There is deliberately no formatting logic here. The canonical spelling
 * ({@code S ..P.. O}, {@code S .... O}, {@code ..P.. O}, {@code … ,, ..k.. v}) and — more
 * importantly — the invariants are the library's, specified in {@code linting.adoc}:
 * <ul>
 *   <li><b>meaning-preserving</b> — {@code parse(format(x)) == parse(x)};</li>
 *   <li><b>idempotent</b> — {@code format(format(x)) == format(x)};</li>
 *   <li><b>prose-safe</b> — a line the reader does not recognise as a triple/command is left
 *       byte-for-byte, and the interiors of {@code !!block} / {@code !!verbatim} / {@code !!off}
 *       regions are never touched;</li>
 *   <li><b>object verbatim</b> — a URL or relative path after the second {@code ..}
 *       ({@code a ..spec.. http://h/x..y}) keeps its internal spacing.</li>
 * </ul>
 *
 * <p>This replaces the former hand-rolled {@code DdotFormatter} (a port of the VS Code
 * reference implementation), which honoured none of those: it split on every {@code ..} in a
 * line, so it mangled objects containing {@code ..}, multi-pair metadata and plain prose.
 */
public final class DdotLintFormatter {

    private DdotLintFormatter() {}

    /**
     * Formats a whole {@code .ddot} document to canonical form. Line separators are {@code \n}
     * (IntelliJ {@code Document} text always is); a trailing newline is preserved.
     */
    public static @NotNull String formatDocument(@NotNull String text) {
        return String.join("\n", new DdotLinter().lint(text).canonicalLines());
    }
}
