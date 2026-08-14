package com.calpano.ddot.asciidoc;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Installs the Asciidoctor extension on its own, the first time the user opens an
 * AsciiDoc file that actually contains a ddot block. Before this existed, preview
 * rendering silently did nothing until the user found
 * <i>Tools → ddot.it → Install AsciiDoc Preview Extension</i> — a setup step nobody
 * can be expected to guess. The menu action stays, as the way to force a reinstall.
 *
 * <p>Registered from {@code META-INF/ddot-asciidoc.xml}, so it exists only when the
 * AsciiDoc plugin does. Two further gates keep us out of projects that have no
 * business carrying our files:
 * <ul>
 *   <li>the opened file must mention a ddot listing block — we never create
 *       {@code .asciidoctor/lib/} in a repo that merely happens to use AsciiDoc;</li>
 *   <li>{@code ddot.asciidoc.autoinstall} in the Registry turns the whole thing off.</li>
 * </ul>
 *
 * <p>We write without asking, but we do say so: a one-off notification names the
 * files and the directory. The AsciiDoc plugin then shows its own trust banner
 * before it will execute them, so nothing runs behind the user's back.
 */
public final class DdotAsciidocAutoInstaller implements FileEditorManagerListener {

    public static final String REGISTRY_KEY = "ddot.asciidoc.autoinstall";

    private static final Logger LOG = Logger.getInstance(DdotAsciidocAutoInstaller.class);

    /** Extensions the AsciiDoc plugin claims. */
    private static final Set<String> ASCIIDOC_EXTENSIONS = Set.of("adoc", "asciidoc", "asc", "ad");

    /**
     * A ddot listing header: {@code [ddot]}, {@code [source,ddot.it]}, … Deliberately
     * looser than {@link com.calpano.ddot.injection.DdotAsciiDocInjector}'s PSI-accurate
     * matching — this is only a "is it worth installing" sniff, and a false positive
     * costs nothing beyond three files the user already wanted.
     */
    private static final Pattern DDOT_BLOCK = Pattern.compile(
            "^\\[\\s*(?:source\\s*,\\s*)?(?:ddot|ddot\\.it|ddotit)\\s*]\\s*$",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    /** Don't scan a whole book to answer a yes/no question. */
    private static final int MAX_SNIFF_CHARS = 512 * 1024;

    @Override
    public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        Project project = source.getProject();
        if (!Registry.is(REGISTRY_KEY, true)) return;
        if (!isAsciidoc(file)) return;
        // Sniffed here, on the EDT, because the Document is already in memory;
        // the decision that uses it is made on the pooled thread, where the
        // filesystem check it may need is allowed.
        boolean mentionsDdot = mentionsDdotBlock(file);

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            if (project.isDisposed()) return;
            // A ddot block is what earns us the right to put files in a repo that
            // has none. Once they ARE there, keeping them current needs no such
            // permission — and matters most in files with no ddot content at all:
            // a stale extension that raises during the Treeprocessor pass fails
            // the whole document load, so every .adoc in the project stops
            // rendering until the copy is refreshed.
            if (!mentionsDdot && !AsciidocExtensionInstaller.isInstalled(project)) return;

            AsciidocExtensionInstaller.Result result = AsciidocExtensionInstaller.install(project);
            switch (result.status()) {
                case INSTALLED -> notifyInstalled(project, result);
                // Nothing to do, and nothing worth a popup: the common steady state,
                // a project without content roots, or a failure the user did not ask
                // for and cannot act on from here. The menu action reports properly.
                case UP_TO_DATE, NO_CONTENT_ROOT, NO_ASCIIDOC_PLUGIN -> {
                }
                case FAILED -> LOG.warn("ddot.it AsciiDoc auto-install failed: " + result.detail());
            }
        });
    }

    private static void notifyInstalled(Project project, AsciidocExtensionInstaller.Result result) {
        String what = result.wasInstalled()
                ? "Updated the preview extension to v" + AsciidocExtensionInstaller.pluginVersion()
                  + " (" + String.join(", ", result.written()) + ") in " + result.libDir() + "."
                : "Installed the preview extension (" + String.join(", ", result.written()) + ") into "
                  + result.libDir() + " so [ddot] blocks render in the AsciiDoc preview.";
        Notification n = new Notification("ddot.it", "ddot.it AsciiDoc preview",
                what + "\nIf the AsciiDoc plugin shows a trust banner, click Enable; then refresh the preview.",
                NotificationType.INFORMATION);
        Notifications.Bus.notify(n, project);
    }

    private static boolean isAsciidoc(VirtualFile file) {
        String ext = file.getExtension();
        return ext != null && ASCIIDOC_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT));
    }

    /**
     * Reads the already-loaded Document rather than the VirtualFile: the file is
     * being opened in an editor, so the text is in memory and this stays cheap and
     * off the disk. No document (binary, too large) means no sniff and no install.
     */
    private static boolean mentionsDdotBlock(VirtualFile file) {
        var document = FileDocumentManager.getInstance().getDocument(file);
        return document != null && containsDdotBlock(document.getCharsSequence());
    }

    static boolean containsDdotBlock(CharSequence text) {
        if (text.length() > MAX_SNIFF_CHARS) {
            text = text.subSequence(0, MAX_SNIFF_CHARS);
        }
        return DDOT_BLOCK.matcher(text).find();
    }
}
