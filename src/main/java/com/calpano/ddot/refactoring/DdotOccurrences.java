package com.calpano.ddot.refactoring;

import com.calpano.ddot.index.DdotEmbeds;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import it.ddot.core.token.DdotTokenizer;
import it.ddot.core.token.Token;
import it.ddot.core.token.TokenName;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Where an entity name occurs, as rename and Find Usages see it (feature-rename.adoc).
 * The spec tokenizer decides what an entity is: a subject, relation, object, metadata
 * key or metadata value. Everything else — prose, {@code ddot.it/off} regions,
 * {@code !!block} bodies, commands — never produces such a token, so it is never
 * touched. Embedded blocks are tokenized chunk by chunk and mapped back to offsets
 * in the host Markdown/AsciiDoc file.
 */
public final class DdotOccurrences {

    private static final Set<TokenName> ENTITY_TOKENS = Set.of(
            TokenName.SUBJECT, TokenName.RELATION, TokenName.OBJECT,
            TokenName.META_RELATION, TokenName.META_OBJECT);

    private DdotOccurrences() {}

    /** One entity occurrence: its text as written and its range in the host file. */
    public record Occurrence(@NotNull String name, int start, int end) {}

    /** An occurrence in a project file. */
    public record FileOccurrence(@NotNull VirtualFile file, @NotNull Occurrence occurrence) {}

    /** Every entity occurrence in a host file's text. Pure — unit-testable. */
    public static @NotNull List<Occurrence> inText(@Nullable String extension, @NotNull String text) {
        int[] lineStarts = lineStarts(text);
        List<Occurrence> out = new ArrayList<>();
        for (DdotEmbeds.Chunk chunk : DdotEmbeds.extractChunks(extension, text)) {
            int chunkLine0 = chunk.firstLine() - 1;
            for (Token t : DdotTokenizer.tokenize(chunk.text(), chunk.startsOff())) {
                if (!ENTITY_TOKENS.contains(t.tokenName())) continue;
                int hostLine = chunkLine0 + t.line();
                if (hostLine >= lineStarts.length) continue;
                int start = lineStarts[hostLine] + t.start();
                int end = lineStarts[hostLine] + t.end();
                // Trim, so the range covers exactly the name.
                while (start < end && Character.isWhitespace(text.charAt(start))) start++;
                while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
                if (start < end) out.add(new Occurrence(text.substring(start, end), start, end));
            }
        }
        return out;
    }

    /** The occurrence covering {@code offset}, or ending right at it (caret after a word). */
    public static @Nullable Occurrence at(@Nullable String extension, @NotNull String text, int offset) {
        for (Occurrence o : inText(extension, text)) {
            if (o.start() <= offset && offset <= o.end()) return o;
        }
        return null;
    }

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
            for (Occurrence o : inText(file.getExtension(), text)) {
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

    private static int[] lineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') starts.add(i + 1);
        }
        int[] out = new int[starts.size()];
        for (int i = 0; i < out.length; i++) out[i] = starts.get(i);
        return out;
    }
}
