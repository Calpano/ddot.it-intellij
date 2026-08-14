package com.calpano.ddot.asciidoc;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Copies the Asciidoctor extension into {@code <contentRoot>/.asciidoctor/lib/}
 * so the JetBrains AsciiDoc plugin discovers it via {@code AsciiDocExtensionService}
 * and renders {@code [ddot]} / {@code [source,ddot]} blocks with role-aware HTML
 * matching our Markdown preview.
 *
 * <p>This is a workaround. The asciidoc plugin doesn't expose an extension point
 * for third-party plugins to register AsciidoctorJ extensions — only
 * {@code html.panel.provider} and {@code asciidocRunner}. The sanctioned path
 * for end-users is to drop {@code .rb} or {@code .jar} files into
 * {@code .asciidoctor/lib/}, gated by the asciidoc plugin's per-project trust
 * banner.
 *
 * <p><b>Long-term (Option C):</b> file an upstream PR adding an
 * {@code org.asciidoc.intellij.asciidoctorExtensions} EP so we can register the
 * extension directly from {@code plugin.xml} — no copy into the user's repo,
 * no trust prompt for our own plugin's extension. Until that lands, this is
 * the cleanest available integration.
 *
 * <p>Pure logic, no UI: {@link com.calpano.ddot.actions.InstallAsciidocExtensionAction}
 * and {@link DdotAsciidocAutoInstaller} both drive it and report in their own way.
 * {@link #install} touches the filesystem — call it off the EDT.
 */
public final class AsciidocExtensionInstaller {

    /**
     * Files copied into {@code <contentRoot>/.asciidoctor/lib/}. They must
     * all land together: {@code ddot-extension.rb} {@code require_relative}'s
     * both {@code ddot-render.rb} (the role-coloured HTML renderer for the
     * BlockProcessor / Treeprocessor fallback) and {@code ddot.rb} (the Rouge
     * lexer that lights up {@code [source,ddot]} blocks when the user has
     * {@code :source-highlighter: rouge} set).
     *
     * <p>{@code ddot.rb} is <b>not</b> under {@code src/main/resources}: the
     * build copies it verbatim from the sibling {@code ddot.it-syntax-tools}
     * checkout (see {@code ddot.rouge-lexer.file} in {@code pom.xml}), where it
     * is conformance-tested against the shared token corpus. The other two are
     * owned by this repo.
     */
    public static final List<String> FILES = List.of(
            "ddot-render.rb",
            "ddot.rb",
            "ddot-extension.rb"
    );

    private static final String RESOURCE_DIR = "/com/calpano/ddot/asciidoc/";
    private static final PluginId ASCIIDOC_PLUGIN_ID = PluginId.getId("org.asciidoctor.intellij.asciidoc");
    private static final PluginId OUR_PLUGIN_ID = PluginId.getId("com.calpano.ddot");

    private AsciidocExtensionInstaller() {
    }

    public enum Status {
        /** At least one file was written. {@link Result#written()} says which. */
        INSTALLED,
        /** Every file already matched what we ship, byte for byte. Nothing touched. */
        UP_TO_DATE,
        NO_ASCIIDOC_PLUGIN,
        NO_CONTENT_ROOT,
        /** A resource was missing from our own jar, or the write failed. */
        FAILED
    }

    /**
     * @param libDir       the target directory, or {@code null} when we never got that far
     * @param written      names of the files actually written this run, in {@link #FILES} order
     * @param wasInstalled whether any of our files were already there when this run started —
     *                     i.e. this was an upgrade rather than a first install
     * @param detail       human-readable cause, set for {@link Status#FAILED}
     */
    public record Result(@NotNull Status status,
                         @Nullable Path libDir,
                         @NotNull List<String> written,
                         boolean wasInstalled,
                         @Nullable String detail) {
    }

    /**
     * Whether this project already carries our extension, at any version.
     *
     * <p>The auto-installer uses this to decide that a refresh is welcome even in a
     * file with no ddot block: once the files are in the repo, a stale copy is our
     * problem to fix. A broken one is worse than absent — an extension that raises
     * during the Treeprocessor pass fails the whole document load, so <em>every</em>
     * {@code .adoc} in the project stops rendering, ddot content or not.
     *
     * <p>Blocking I/O — call off the EDT.
     */
    public static boolean isInstalled(@NotNull Project project) {
        VirtualFile root = pickContentRoot(project);
        if (root == null || root.getPath() == null) return false;
        Path libDir = Path.of(root.getPath(), ".asciidoctor", "lib");
        return FILES.stream().anyMatch(name -> Files.isRegularFile(libDir.resolve(name)));
    }

    public static boolean isAsciidocPluginInstalled() {
        return PluginManagerCore.isPluginInstalled(ASCIIDOC_PLUGIN_ID);
    }

    public static @NotNull String pluginVersion() {
        var descriptor = PluginManagerCore.getPlugin(OUR_PLUGIN_ID);
        return descriptor != null ? descriptor.getVersion() : "unknown";
    }

    /**
     * Installs any file whose on-disk bytes differ from what we ship, and leaves
     * the rest alone. Idempotent, so it is safe to call on every AsciiDoc file
     * open: a project that is already current comes back {@link Status#UP_TO_DATE}
     * without a single write. Because each file carries a version stamp, a plugin
     * upgrade makes every file differ and so refreshes the whole set.
     *
     * <p>Blocking I/O — must not be called on the EDT.
     */
    public static @NotNull Result install(@NotNull Project project) {
        if (!isAsciidocPluginInstalled()) {
            return new Result(Status.NO_ASCIIDOC_PLUGIN, null, List.of(), false, null);
        }
        VirtualFile root = pickContentRoot(project);
        if (root == null) {
            return new Result(Status.NO_CONTENT_ROOT, null, List.of(), false, null);
        }

        // Stamp the installed copies with the plugin version so users can
        // `head -1 .asciidoctor/lib/ddot-extension.rb` to verify which build is
        // live and rule out stale-install confusion. It doubles as the
        // up-to-date check below: a version bump changes every file.
        byte[] stamp = ("# ddot.it plugin version: " + pluginVersion() + "\n").getBytes(StandardCharsets.UTF_8);

        Path libDir = Path.of(Objects.requireNonNull(root.getPath()), ".asciidoctor", "lib");
        boolean wasInstalled = FILES.stream().anyMatch(name -> Files.isRegularFile(libDir.resolve(name)));
        List<String> written = new ArrayList<>();
        try {
            for (String name : FILES) {
                String resource = RESOURCE_DIR + name;
                byte[] payload;
                try (InputStream in = AsciidocExtensionInstaller.class.getResourceAsStream(resource)) {
                    if (in == null) {
                        return new Result(Status.FAILED, libDir, List.copyOf(written), wasInstalled,
                                "Bundled resource missing: " + resource);
                    }
                    payload = in.readAllBytes();
                }
                // Concatenated as BYTES, never decoded: ddot.rb carries non-ASCII
                // (→, —, ;;-spec prose) and a round trip through the platform
                // default charset would corrupt it.
                byte[] content = new byte[stamp.length + payload.length];
                System.arraycopy(stamp, 0, content, 0, stamp.length);
                System.arraycopy(payload, 0, content, stamp.length, payload.length);

                Path target = libDir.resolve(name);
                if (isUpToDate(target, content)) continue;

                Files.createDirectories(libDir);
                Files.write(target, content);
                written.add(name);
                ApplicationManager.getApplication().invokeLater(() ->
                        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target));
            }
        } catch (IOException ex) {
            return new Result(Status.FAILED, libDir, List.copyOf(written), wasInstalled,
                    "Failed to install extension: " + ex.getMessage());
        }

        return new Result(written.isEmpty() ? Status.UP_TO_DATE : Status.INSTALLED,
                libDir, List.copyOf(written), wasInstalled, null);
    }

    private static boolean isUpToDate(Path target, byte[] content) {
        try {
            return Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), content);
        } catch (IOException ex) {
            return false; // unreadable — rewrite it
        }
    }

    public static @Nullable VirtualFile pickContentRoot(@NotNull Project project) {
        VirtualFile[] roots = ProjectRootManager.getInstance(project).getContentRoots();
        if (roots.length > 0) return roots[0];
        String basePath = project.getBasePath();
        return basePath == null ? null : LocalFileSystem.getInstance().findFileByPath(basePath);
    }
}
