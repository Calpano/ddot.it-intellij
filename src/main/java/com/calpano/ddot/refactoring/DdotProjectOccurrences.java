package com.calpano.ddot.refactoring;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import it.ddot.core.embed.DdotEmbeds;
import it.ddot.core.embed.DdotOccurrences;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Where an entity name occurs across the project, as rename and Find Usages see it (feature-rename.adoc).
 * What counts as an occurrence within one file is the core library's {@link DdotOccurrences}; this adds the
 * project's files and their unsaved editor content.
 */
public final class DdotProjectOccurrences {

    private DdotProjectOccurrences() {}

    /** An occurrence in a project file. */
    public record FileOccurrence(@NotNull VirtualFile file, @NotNull DdotOccurrences.Occurrence occurrence) {}

    /**
     * Occurrences of {@code name} across the project, matched case-insensitively — callers
     * split exact matches from case variants. Reads unsaved editor content where there is any.
     */
    public static @NotNull List<FileOccurrence> inProject(@NotNull Project project, @NotNull String name) {
        String key = name.toLowerCase(Locale.ROOT);
        List<FileOccurrence> out = new ArrayList<>();
        for (VirtualFile file : candidateFiles(project)) {
            ProgressManager.checkCanceled();
            String text = currentText(file);
            if (text == null || !text.toLowerCase(Locale.ROOT).contains(key)) continue;
            for (DdotOccurrences.Occurrence o : DdotOccurrences.inText(file.getExtension(), text)) {
                if (o.name().toLowerCase(Locale.ROOT).equals(key)) out.add(new FileOccurrence(file, o));
            }
        }
        return out;
    }

    private static Collection<VirtualFile> candidateFiles(@NotNull Project project) {
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        Set<VirtualFile> files = new LinkedHashSet<>();
        for (String ext : DdotEmbeds.extensions()) {
            files.addAll(FilenameIndex.getAllFilesByExt(project, ext, scope));
        }
        return files;
    }

    private static @Nullable String currentText(@NotNull VirtualFile file) {
        Document doc = FileDocumentManager.getInstance().getDocument(file);
        return doc == null ? null : doc.getText();
    }
}
