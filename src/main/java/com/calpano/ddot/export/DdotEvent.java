package com.calpano.ddot.export;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Triple event matching the schema documented at
 * <a href="https://ddot.it/developer-guide.html#events">ddot.it events</a>.
 */
public final class DdotEvent {
    public final @NotNull String from;
    public final @Nullable String type;
    public final @NotNull String to;
    public final @NotNull List<MetaPair> meta = new ArrayList<>();
    public final @NotNull String kind;
    public final @NotNull String source;
    public final int location;

    public DdotEvent(@NotNull String from, @Nullable String type, @NotNull String to,
                     @NotNull String kind, @NotNull String source, int location) {
        this.from = from;
        this.type = type;
        this.to = to;
        this.kind = kind;
        this.source = source;
        this.location = location;
    }

    public record MetaPair(@NotNull String type, @NotNull String to) {}
}
