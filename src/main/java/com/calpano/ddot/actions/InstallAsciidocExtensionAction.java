package com.calpano.ddot.actions;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Installs {@code ddot-extension.rb} into {@code <contentRoot>/.asciidoctor/lib/}
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
 * no trust prompt for our own plugin's extension. Until that lands, this action
 * is the cleanest available integration.
 */
public final class InstallAsciidocExtensionAction extends AnAction {

    /**
     * Files copied into {@code <contentRoot>/.asciidoctor/lib/}. They must
     * all land together: {@code ddot-extension.rb} {@code require_relative}'s
     * both {@code ddot-render.rb} (the role-coloured HTML renderer for the
     * BlockProcessor / Treeprocessor fallback) and {@code ddot.rb} (the Rouge
     * lexer that lights up {@code [source,ddot]} blocks when the user has
     * {@code :source-highlighter: rouge} set).
     */
    private static final String[] FILES = {
            "ddot-render.rb",
            "ddot.rb",
            "ddot-extension.rb"
    };
    private static final String RESOURCE_DIR = "/com/calpano/ddot/asciidoc/";
    private static final PluginId ASCIIDOC_PLUGIN_ID = PluginId.getId("org.asciidoctor.intellij.asciidoc");
    private static final PluginId OUR_PLUGIN_ID = PluginId.getId("com.calpano.ddot");

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        if (!PluginManagerCore.isPluginInstalled(ASCIIDOC_PLUGIN_ID)) {
            notify(project, NotificationType.WARNING,
                    "The JetBrains AsciiDoc plugin isn't installed — the preview extension has nothing to plug into.\n" +
                    "Install it via Settings → Plugins → search 'AsciiDoc', then run this action again.");
            return;
        }

        VirtualFile root = pickContentRoot(project);
        if (root == null) {
            notify(project, NotificationType.ERROR,
                    "No content root found — open a project before installing the AsciiDoc preview extension.");
            return;
        }

        String pluginVersion = pluginVersion();

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                Path libDir = Path.of(Objects.requireNonNull(root.getPath()), ".asciidoctor", "lib");
                Files.createDirectories(libDir);

                // Stamp the installed copies with the plugin version so users
                // can `head -1 .asciidoctor/lib/ddot-extension.rb` to verify
                // which build is live and rule out stale-install confusion.
                String stamp = "# ddot.it plugin version: " + pluginVersion + "\n";

                for (String name : FILES) {
                    Path target = libDir.resolve(name);
                    String resource = RESOURCE_DIR + name;
                    byte[] payload;
                    try (InputStream in = InstallAsciidocExtensionAction.class.getResourceAsStream(resource)) {
                        if (in == null) {
                            notify(project, NotificationType.ERROR,
                                    "Bundled resource missing: " + resource);
                            return;
                        }
                        payload = in.readAllBytes();
                    }
                    Files.write(target, (stamp + new String(payload)).getBytes());
                    ApplicationManager.getApplication().invokeLater(() ->
                            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target));
                }

                notify(project, NotificationType.INFORMATION,
                        "Installed v" + pluginVersion + " into " + libDir + " ("
                        + String.join(", ", FILES) + ").\n" +
                        "If the AsciiDoc plugin shows a trust banner, click Enable; "
                        + "then refresh the .adoc preview.");
            } catch (IOException ex) {
                notify(project, NotificationType.ERROR,
                        "Failed to install extension: " + ex.getMessage());
            }
        });
    }

    private static String pluginVersion() {
        var descriptor = PluginManagerCore.getPlugin(OUR_PLUGIN_ID);
        return descriptor != null ? descriptor.getVersion() : "unknown";
    }

    private static @Nullable VirtualFile pickContentRoot(Project project) {
        VirtualFile[] roots = ProjectRootManager.getInstance(project).getContentRoots();
        if (roots.length > 0) return roots[0];
        String basePath = project.getBasePath();
        return basePath == null ? null : LocalFileSystem.getInstance().findFileByPath(basePath);
    }

    private static void notify(Project project, NotificationType type, String message) {
        Notification n = new Notification("ddot.it", "ddot.it AsciiDoc preview", message, type);
        Notifications.Bus.notify(n, project);
    }
}
