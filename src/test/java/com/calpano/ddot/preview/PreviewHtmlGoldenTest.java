package com.calpano.ddot.preview;

import com.calpano.ddot.export.DdotEventExporter;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Cross-implementation golden corpus checks against
 * {@code ../ddot.it/test-data/cases/}. Each case has:
 * <ul>
 *   <li>{@code input.ddot} — the source,</li>
 *   <li>{@code expected.body.html} — role-classed body HTML, also produced
 *       by the Ruby renderer ({@code ddot-render.rb}) and asserted to match
 *       {@link DdotMarkdownPreviewRenderer#body(String)},</li>
 *   <li>{@code expected.events.jsonl} — triple events as defined at
 *       https://ddot.it/developer-guide.html#events, asserted to match
 *       {@link DdotEventExporter#parse} + {@link DdotEventExporter#toJsonl}.</li>
 * </ul>
 *
 * <p>Corpus path comes from the {@code ddot.test-data.dir} system property
 * (set by surefire from a pom.xml property; default is
 * {@code ${project.basedir}/../ddot.it/test-data}). When the directory is
 * absent we log a warning and pass vacuously, so a fresh checkout of just
 * this repo doesn't fail.
 *
 * <p>To bootstrap or refresh the {@code expected.events.jsonl} files after
 * changing {@link DdotEventExporter} (the canonical Java implementation):
 * {@code mvn test -Dgolden.regen=true}. The HTML side is regenerated via
 * {@code ruby test-data/regenerate-html.rb} in the corpus repo
 * (the Ruby renderer is canonical for HTML).
 */
public final class PreviewHtmlGoldenTest {

    private static final String DIR_PROP = "ddot.test-data.dir";
    private static final String REGEN_PROP = "golden.regen";
    private static final String EVENT_KIND = "ddot";
    private static final String EVENT_SOURCE = "input.ddot";

    @Test
    public void matchesGoldenCorpus() throws IOException {
        String configured = System.getProperty(DIR_PROP);
        if (configured == null || configured.isBlank()) {
            warn("System property `" + DIR_PROP + "` is not set; skipping golden-corpus checks.");
            return;
        }
        Path casesDir = Path.of(configured, "cases");
        if (!Files.isDirectory(casesDir)) {
            warn("Test-data directory not found at " + casesDir
                    + " — skipping golden-corpus checks. Clone https://github.com/calpano/ddot.it"
                    + " as a sibling of this repo, or override -D" + DIR_PROP + "=/abs/path.");
            return;
        }

        boolean regen = Boolean.parseBoolean(System.getProperty(REGEN_PROP, "false"));

        List<Path> caseDirs;
        try (Stream<Path> s = Files.list(casesDir)) {
            caseDirs = s.filter(Files::isDirectory)
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
        if (caseDirs.isEmpty()) {
            warn("No cases under " + casesDir);
            return;
        }

        List<String> failures = new ArrayList<>();
        for (Path caseDir : caseDirs) {
            String name = caseDir.getFileName().toString();
            Path input = caseDir.resolve("input.ddot");
            if (!Files.isRegularFile(input)) {
                warn("skip " + name + ": missing input.ddot");
                continue;
            }
            String inputText = Files.readString(input);

            checkOrRegen(caseDir.resolve("expected.body.html"),
                    DdotMarkdownPreviewRenderer.body(inputText),
                    name + " [html]", regen, failures);

            checkOrRegen(caseDir.resolve("expected.events.jsonl"),
                    DdotEventExporter.toJsonl(
                            DdotEventExporter.parse(inputText, EVENT_KIND, EVENT_SOURCE)),
                    name + " [events]", regen, failures);
        }

        if (regen) {
            // In regen mode we never fail — we wrote whatever the renderers produced.
            return;
        }
        if (!failures.isEmpty()) {
            fail(failures.size() + " case(s) diverged from corpus: " + String.join(", ", failures)
                    + ". Either fix the Java implementation to match,"
                    + " or update the canonical source (ddot-render.rb for HTML, DdotEventExporter for events)"
                    + " and run regenerate (`ruby regenerate.rb` for HTML; `mvn test -Dgolden.regen=true` for events).");
        }
    }

    private static void checkOrRegen(Path expected, String actual, String label,
                                     boolean regen, List<String> failures) throws IOException {
        if (regen) {
            String current = Files.isRegularFile(expected) ? Files.readString(expected) : null;
            if (actual.equals(current)) {
                System.out.println("[golden] ok    " + label);
            } else {
                Files.writeString(expected, actual);
                System.out.println("[golden] wrote " + label + " → " + expected.getFileName());
            }
            return;
        }
        if (!Files.isRegularFile(expected)) {
            failures.add(label + " (missing " + expected.getFileName() + ")");
            System.err.println("[golden] MISSING " + label + " — run with -Dgolden.regen=true");
            return;
        }
        String want = Files.readString(expected);
        try {
            assertEquals("case: " + label, want, actual);
        } catch (AssertionError e) {
            failures.add(label);
            System.err.println("[golden] FAIL " + label);
            System.err.println("  expected: " + want);
            System.err.println("  actual  : " + actual);
        }
    }

    private static void warn(String msg) {
        System.err.println("[golden] WARNING: " + msg);
    }
}
