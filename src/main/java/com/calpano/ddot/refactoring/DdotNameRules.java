package com.calpano.ddot.refactoring;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * Which new names a rename accepts (feature-rename.adoc, "Validating the new name").
 * Each rejected shape would change how the line parses, so the rename would alter
 * more than the name.
 */
public final class DdotNameRules {

    private static final Pattern COMMAND = Pattern.compile("^(?:(?:https?://)?(?:www\\.)?ddot\\.it/|!!)");

    private DdotNameRules() {}

    /** The problem with {@code name} (already trimmed), or {@code null} when it is acceptable. */
    public static @Nullable String problem(@NotNull String name) {
        if (name.isEmpty()) return "The name must not be empty.";
        if (name.contains("\n") || name.contains("\r")) return "The name must be on one line.";
        for (String sep : new String[]{"..", ",,", "::"}) {
            if (name.contains(sep)) return "The name must not contain '" + sep + "'.";
        }
        if (name.startsWith(".") || name.endsWith(".")) return "The name must not start or end with '.'.";
        if (COMMAND.matcher(name).find()) return "The name must not be a command (!!… or ddot.it/…).";
        return null;
    }
}
