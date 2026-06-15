package com.calpano.ddot.cj;

import com.graphinout.base.cj.document.CjDocuments;
import com.graphinout.base.cj.document.ICjDocument;
import com.graphinout.reader.ddot.DDotOutput;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Converts Connected JSON (CJ) text into ddot.it text using graphinout's canonical
 * mapping: CJ JSON is parsed into the native {@link ICjDocument} model, which the
 * ddot writer ({@link DDotOutput}) serializes. This is graphinout's own round-trip
 * path — the plugin does not define the mapping, it only drives it.
 *
 * <p>Pure and IDE-free so it can be unit-tested directly; the IntelliJ action is a
 * thin wrapper around {@link #convert(String, String)}.
 */
public final class CjToDdot {
    private CjToDdot() {}

    /**
     * @param contentName a human-readable name for the input (used by graphinout in
     *                    error messages), e.g. the source file name
     * @param cjJson      the Connected JSON document text
     * @return the equivalent ddot.it document text
     * @throws IOException if {@code cjJson} is not valid Connected JSON
     */
    public static @NotNull String convert(@NotNull String contentName, @NotNull String cjJson) throws IOException {
        ICjDocument doc = CjDocuments.parseCjJsonString(contentName, cjJson);
        return new DDotOutput(doc).toDDot();
    }
}
