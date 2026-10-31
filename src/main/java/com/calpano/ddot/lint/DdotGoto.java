package com.calpano.ddot.lint;

import it.ddot.core.lint.Location;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Jumping to a stated location, and the tooltip link that does it ({@code #ddot-goto/<url>:<line>}). */
public final class DdotGoto {

    public static final String LINK_PREFIX = "#ddot-goto/";

    private DdotGoto() {}

    public static @NotNull String link(@NotNull Location loc) {
        return LINK_PREFIX + URLEncoder.encode(loc.url(), StandardCharsets.UTF_8) + ":" + loc.line();
    }

    /** Parse the part after {@link #LINK_PREFIX}. */
    public static @Nullable Location parse(@NotNull String refSuffix) {
        int colon = refSuffix.lastIndexOf(':');
        if (colon < 0) return null;
        try {
            return new Location(URLDecoder.decode(refSuffix.substring(0, colon), StandardCharsets.UTF_8),
                    Integer.parseInt(refSuffix.substring(colon + 1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static boolean open(@NotNull Project project, @NotNull Location loc) {
        VirtualFile file = VirtualFileManager.getInstance().findFileByUrl(loc.url());
        if (file == null || !file.isValid()) return false;
        new OpenFileDescriptor(project, file, Math.max(0, loc.line() - 1), 0).navigate(true);
        return true;
    }

    /** {@code path/in/project.ddot:12} */
    public static @NotNull String label(@NotNull Project project, @NotNull Location loc) {
        VirtualFile file = VirtualFileManager.getInstance().findFileByUrl(loc.url());
        if (file == null) return loc.url() + ":" + loc.line();
        VirtualFile base = ProjectUtil.guessProjectDir(project);
        String path = base != null && VfsUtilCore.isAncestor(base, file, false)
                ? VfsUtilCore.getRelativePath(file, base) : file.getPresentableUrl();
        return path + ":" + loc.line();
    }
}
