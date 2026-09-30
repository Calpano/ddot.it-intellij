package com.calpano.ddot.formatter;

import org.jetbrains.annotations.NotNull;

/**
 * What <em>Reformat Code</em> and <em>Format Document</em> do to a {@code .ddot} file: every triple
 * becomes canonical and self-contained, {@code SUBJECT ..relation.. object}. Continuation lines get
 * their inherited subject ({@link DdotToFullTriples}), then the linter's FIX path
 * ({@link DdotLintFormatter}) normalises the spacing.
 */
public final class DdotReformat {

    private DdotReformat() {}

    public static @NotNull String format(@NotNull String text) {
        return DdotLintFormatter.formatDocument(DdotToFullTriples.expandDocument(text));
    }
}
