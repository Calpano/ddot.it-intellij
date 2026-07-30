package com.calpano.ddot.index;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A ddot triple as indexed: subject, optional predicate (null = untyped link), object. */
public record DdotTriple(@NotNull String from, @Nullable String type, @NotNull String to) {}
