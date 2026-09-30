package com.calpano.ddot.documentation;

import com.calpano.ddot.psi.DdotEntity;
import com.intellij.lang.documentation.AbstractDocumentationProvider;
import com.intellij.lang.documentation.ExternalDocumentationHandler;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Hover/quick-doc provider. On an entity (subject, relation or object) it shows
 * what the project knowledge graph states about it — its relations and, inverse,
 * the relations pointing at it — with every item linking to where the triple is
 * stated. Elsewhere it explains the line shape (typed link, simple link, or
 * metadata) the cursor is on.
 */
public final class DdotDocumentationProvider extends AbstractDocumentationProvider
        implements ExternalDocumentationHandler {

    @Override
    public @Nullable String generateDoc(PsiElement element, @Nullable PsiElement originalElement) {
        PsiElement target = originalElement != null ? originalElement : element;
        if (target == null) return null;

        DdotEntity entity = element instanceof DdotEntity e ? e : PsiTreeUtil.getParentOfType(target, DdotEntity.class, false);
        if (entity != null) {
            String entityDoc = DdotEntityFacts.html(entity);
            if (entityDoc != null) return entityDoc;
        }

        Document doc = PsiDocumentManager.getInstance(target.getProject()).getDocument(target.getContainingFile());
        if (doc == null) return null;
        int offset = target.getTextRange().getStartOffset();
        if (offset < 0 || offset >= doc.getTextLength()) return null;

        int lineNum = doc.getLineNumber(offset);
        int s = doc.getLineStartOffset(lineNum);
        int e = doc.getLineEndOffset(lineNum);
        String line = doc.getCharsSequence().subSequence(s, e).toString();

        return docForLine(line);
    }

    @Override
    public boolean handleExternalLink(PsiManager psiManager, String link, PsiElement context) {
        return link != null && DdotTripleNavigator.follow(psiManager.getProject(), link);
    }

    @Override
    public boolean canFetchDocumentationLink(String link) {
        return false;
    }

    @Override
    public @NotNull String fetchExternalDocumentation(@NotNull String link, @Nullable PsiElement element) {
        return "";
    }

    private static @Nullable String docForLine(String line) {
        if (line.contains("..")) {
            String[] parts = line.split("\\s*\\.{2,}\\s*");
            if (parts.length >= 3) {
                return """
                        <b>Typed Link</b>: <code>subject .. type .. object</code><br>
                        Creates a typed link with:
                        <ul>
                          <li><b>Subject</b>: %s</li>
                          <li><b>Type</b>: %s</li>
                          <li><b>Object</b>: %s</li>
                        </ul>
                        """.formatted(escape(parts[0].trim()), escape(parts[1].trim()),
                        escape(joinFrom(parts, 2, " .. ")));
            } else if (parts.length == 2) {
                return """
                        <b>Simple Link</b>: <code>subject .... object</code><br>
                        Creates an untyped link between two entities.
                        """;
            }
        }
        if (line.contains(",,")) {
            return """
                    <b>Metadata</b>: <code>triple ,, ..key.. value</code><br>
                    Adds metadata or annotations to a triple. Several inline pairs are
                    separated by <code>;;</code>: <code>,, ..k1.. v1 ;; ..k2.. v2</code>.
                    """;
        }
        return null;
    }

    private static String joinFrom(String[] parts, int from, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < parts.length; i++) {
            if (i > from) sb.append(sep);
            sb.append(parts[i]);
        }
        return sb.toString().trim();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
