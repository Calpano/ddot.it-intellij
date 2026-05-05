package com.calpano.ddot.documentation;

import com.calpano.ddot.psi.DdotEntity;
import com.intellij.lang.documentation.AbstractDocumentationProvider;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.Nullable;

/**
 * Hover/quick-doc provider — explains the line shape (typed link, simple link,
 * or metadata) the cursor is on.
 */
public final class DdotDocumentationProvider extends AbstractDocumentationProvider {

    @Override
    public @Nullable String generateDoc(PsiElement element, @Nullable PsiElement originalElement) {
        PsiElement target = originalElement != null ? originalElement : element;
        if (target == null) return null;

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
                    <b>Metadata</b>: <code>triple ,, key :: value</code><br>
                    Adds metadata or annotations to a triple.
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
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
