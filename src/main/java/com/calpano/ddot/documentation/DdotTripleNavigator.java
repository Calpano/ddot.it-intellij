package com.calpano.ddot.documentation;

import com.calpano.ddot.completion.DdotKgraphService;
import com.calpano.ddot.refactoring.DdotRenameHandler;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.ui.SimpleListCellRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Links in the entity popup and where they lead. A link names a triple pattern
 * ({@code subject/relation/object}, an empty slot is a wildcard); following it
 * jumps to the place the matching triple is stated, or offers a chooser when it
 * is stated in several places.
 */
final class DdotTripleNavigator {

    static final String PROTOCOL = "ddot-triple:";
    static final String RENAME_PROTOCOL = "ddot-rename:";

    private DdotTripleNavigator() {}

    static @NotNull String link(@Nullable String subject, @Nullable String relation, @Nullable String object) {
        return PROTOCOL + encode(subject) + "/" + encode(relation) + "/" + encode(object);
    }

    /** A link that starts the project-wide rename of {@code name}. */
    static @NotNull String renameLink(@NotNull String name) {
        return RENAME_PROTOCOL + encode(name);
    }

    /** Follow a {@link #link} or {@link #renameLink}; {@code false} when {@code url} is neither. Runs on the EDT. */
    static boolean follow(@NotNull Project project, @NotNull String url) {
        if (url.startsWith(RENAME_PROTOCOL)) {
            String name = decode(url.substring(RENAME_PROTOCOL.length()));
            if (name != null) DdotRenameHandler.start(project, name, null);
            return true;
        }
        if (!url.startsWith(PROTOCOL)) return false;
        String[] slots = url.substring(PROTOCOL.length()).split("/", -1);
        if (slots.length != 3) return false;
        DdotKgraphService graph = DdotKgraphService.getInstance(project);
        List<DdotKgraphService.Location> locations = ReadAction.compute(
                () -> graph.locations(decode(slots[0]), decode(slots[1]), decode(slots[2])));
        navigate(project, locations);
        return true;
    }

    private static void navigate(@NotNull Project project, @NotNull List<DdotKgraphService.Location> locations) {
        if (locations.isEmpty()) {
            JBPopupFactory.getInstance().createMessage("This triple is no longer stated anywhere").showInFocusCenter();
            return;
        }
        if (locations.size() == 1) {
            open(project, locations.getFirst());
            return;
        }
        Map<DdotKgraphService.Location, String> labels = new LinkedHashMap<>();
        ReadAction.run(() -> locations.forEach(loc -> labels.put(loc, label(project, loc))));
        JBPopupFactory.getInstance()
                .createPopupChooserBuilder(locations)
                .setTitle("Stated In")
                .setRenderer(SimpleListCellRenderer.create("", labels::get))
                .setItemChosenCallback(loc -> open(project, loc))
                .createPopup()
                .showInFocusCenter();
    }

    private static void open(@NotNull Project project, @NotNull DdotKgraphService.Location loc) {
        VirtualFile file = VirtualFileManager.getInstance().findFileByUrl(loc.url());
        if (file == null || !file.isValid()) return;
        new OpenFileDescriptor(project, file, Math.max(0, loc.line() - 1), 0).navigate(true);
    }

    /** {@code path/to/file.ddot:12  Alice ..knows.. Bob} */
    private static @NotNull String label(@NotNull Project project, @NotNull DdotKgraphService.Location loc) {
        VirtualFile file = VirtualFileManager.getInstance().findFileByUrl(loc.url());
        if (file == null) return loc.url() + ":" + loc.line();
        VirtualFile base = ProjectUtil.guessProjectDir(project);
        String path = base != null && VfsUtilCore.isAncestor(base, file, false)
                ? VfsUtilCore.getRelativePath(file, base) : file.getPresentableUrl();
        String text = "";
        Document doc = FileDocumentManager.getInstance().getDocument(file);
        int line = loc.line() - 1;
        if (doc != null && line >= 0 && line < doc.getLineCount()) {
            text = doc.getText(new TextRange(
                    doc.getLineStartOffset(line), doc.getLineEndOffset(line))).strip();
        }
        return path + ":" + loc.line() + (text.isEmpty() ? "" : "  " + text);
    }

    private static String encode(@Nullable String s) {
        return s == null ? "" : URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static @Nullable String decode(String s) {
        return s.isEmpty() ? null : URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
