package com.calpano.ddot.actions;

import com.calpano.ddot.asciidoc.AsciidocExtensionInstaller;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Manual entry point for
 * {@link AsciidocExtensionInstaller} — <i>Tools → ddot.it → Install AsciiDoc
 * Preview Extension</i>.
 *
 * <p>Since {@link com.calpano.ddot.asciidoc.DdotAsciidocAutoInstaller} does this
 * by itself when a ddot-bearing AsciiDoc file is opened, this action is no longer
 * the way users are expected to get set up. It stays for the cases automation
 * can't serve: repairing a hand-edited or deleted copy, installing into a project
 * whose {@code .adoc} files don't carry a ddot block yet, and — unlike the silent
 * auto-install — telling the user exactly what happened, including when there was
 * nothing to do.
 */
public final class InstallAsciidocExtensionAction extends AnAction {

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

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            if (project.isDisposed()) return;
            AsciidocExtensionInstaller.Result result = AsciidocExtensionInstaller.install(project);
            switch (result.status()) {
                case INSTALLED -> notify(project, NotificationType.INFORMATION,
                        "Installed v" + AsciidocExtensionInstaller.pluginVersion() + " into " + result.libDir()
                        + " (" + String.join(", ", result.written()) + ").\n"
                        + "If the AsciiDoc plugin shows a trust banner, click Enable; "
                        + "then refresh the .adoc preview.");
                case UP_TO_DATE -> notify(project, NotificationType.INFORMATION,
                        "Already up to date: v" + AsciidocExtensionInstaller.pluginVersion()
                        + " is installed in " + result.libDir() + ".");
                case NO_ASCIIDOC_PLUGIN -> notify(project, NotificationType.WARNING,
                        "The JetBrains AsciiDoc plugin isn't installed — the preview extension has nothing to plug into.\n"
                        + "Install it via Settings → Plugins → search 'AsciiDoc', then run this action again.");
                case NO_CONTENT_ROOT -> notify(project, NotificationType.ERROR,
                        "No content root found — open a project before installing the AsciiDoc preview extension.");
                case FAILED -> notify(project, NotificationType.ERROR, result.detail());
            }
        });
    }

    private static void notify(Project project, NotificationType type, String message) {
        Notification n = new Notification("ddot.it", "ddot.it AsciiDoc preview", message, type);
        Notifications.Bus.notify(n, project);
    }
}
