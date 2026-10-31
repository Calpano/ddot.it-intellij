package com.calpano.ddot.lint;

import com.calpano.ddot.completion.DdotKgraphService;
import com.calpano.ddot.psi.DdotFile;
import com.calpano.ddot.refactoring.DdotRenameProcessor;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.injected.editor.DocumentWindow;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Segment;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import it.ddot.core.lint.DdotAnalysis;
import it.ddot.core.lint.Finding;
import it.ddot.core.lint.Location;
import it.ddot.core.lint.Severity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shows the warnings the core library's {@link DdotAnalysis} finds: the linter's DL010–DL012, incomplete
 * triples, cardinality violations, letter-case variants and relations under both {@code links to} and
 * {@code is linked from}. What is a problem, and where, is decided there — {@code ddot lint} prints the same
 * findings. This class only maps a finding's host-file position into the editor, links the places it names,
 * and offers the fixes that need the IDE: jump to a statement, unify a spelling.
 *
 * <p>Runs once per ddot.it file, including ddot blocks embedded in Markdown and AsciiDoc, whose host-file
 * findings are mapped into the block.
 */
public final class DdotFindingsAnnotator implements Annotator {

    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (!(element instanceof DdotFile file)) return;
        Project project = file.getProject();
        PsiFile host = InjectedLanguageManager.getInstance(project).getTopLevelFile(file);
        VirtualFile vf = host == null ? null : host.getVirtualFile();
        if (vf == null) return;
        PsiDocumentManager pdm = PsiDocumentManager.getInstance(project);
        Document doc = pdm.getDocument(file);
        Document hostDoc = pdm.getDocument(host);
        if (doc == null || hostDoc == null) return;

        DdotKgraphService service = DdotKgraphService.getInstance(project);
        List<Finding> findings = service.findings(vf.getUrl(), vf.getExtension(), hostDoc.getText());
        for (Finding f : findings) {
            TextRange hostRange = hostRange(hostDoc, f);
            if (hostRange == null) continue;
            TextRange range = doc instanceof DocumentWindow w ? injected(w, hostRange) : hostRange;
            if (range == null) continue;
            annotate(project, service, f, hostDoc.getText(hostRange), range, holder);
        }
    }

    /** Where a finding is in the host document; a whole-line finding covers the line without its indentation. */
    private static @Nullable TextRange hostRange(Document hostDoc, Finding f) {
        int line = f.line() - 1;
        if (line < 0 || line >= hostDoc.getLineCount()) return null;
        int lineStart = hostDoc.getLineStartOffset(line), lineEnd = hostDoc.getLineEndOffset(line);
        int start, end;
        if (f.isWholeLine()) {
            CharSequence text = hostDoc.getCharsSequence();
            start = lineStart;
            end = lineEnd;
            while (start < end && Character.isWhitespace(text.charAt(start))) start++;
            while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
        } else {
            start = Math.min(lineStart + f.startColumn(), lineEnd);
            end = Math.min(lineStart + f.endColumn(), lineEnd);
        }
        return start < end ? new TextRange(start, end) : null;
    }

    /** The part of a host range that lies in this injected block, in the block's offsets; null if none does. */
    private static @Nullable TextRange injected(DocumentWindow w, TextRange hostRange) {
        for (Segment s : w.getHostRanges()) {
            int start = Math.max(hostRange.getStartOffset(), s.getStartOffset());
            int end = Math.min(hostRange.getEndOffset(), s.getEndOffset());
            if (start < end) return new TextRange(w.hostToInjected(start), w.hostToInjected(end));
        }
        return null;
    }

    private static void annotate(Project project, DdotKgraphService service, Finding f, String flagged,
                                 TextRange range, AnnotationHolder holder) {
        List<Location> related = f.related().stream().filter(l -> !l.equals(f.location())).toList();
        String phrase = relatedPhrase(f.ruleId());
        StringBuilder plain = new StringBuilder(f.message());
        StringBuilder html = new StringBuilder("<html>").append(StringUtil.escapeXmlEntities(f.message()));
        if (!related.isEmpty()) {
            plain.append(" (").append(phrase).append(' ');
            html.append("<br>").append(StringUtil.capitalize(phrase)).append(' ');
            for (int i = 0; i < related.size(); i++) {
                String label = DdotGoto.label(project, related.get(i));
                if (i > 0) {
                    plain.append(", ");
                    html.append(", ");
                }
                plain.append(label);
                html.append("<a href=\"").append(DdotGoto.link(related.get(i))).append("\">")
                        .append(StringUtil.escapeXmlEntities(label)).append("</a>");
            }
            plain.append(')');
        }
        HighlightSeverity severity = f.severity() == Severity.ERROR ? HighlightSeverity.ERROR : HighlightSeverity.WARNING;
        var builder = holder.newAnnotation(severity, plain.toString())
                .range(range)
                .tooltip(html.append("</html>").toString());
        if (DdotAnalysis.CASE_VARIANT.equals(f.ruleId())) {
            DdotAnalysis analysis = service.analysis();
            Map<String, Location> spellings = analysis == null ? null
                    : analysis.caseVariantGroups().get(flagged.toLowerCase(Locale.ROOT));
            if (spellings != null) for (String spelling : spellings.keySet()) builder = builder.withFix(new UnifySpelling(spelling));
        } else {
            for (Location loc : related) {
                builder = builder.withFix(new GoToStatement(loc, DdotGoto.label(project, loc), goToFamily(f.ruleId())));
            }
        }
        builder.create();
    }

    /** How the places a finding names are introduced: "cardinality stated at schema.ddot:2". */
    private static String relatedPhrase(String ruleId) {
        return switch (ruleId) {
            case DdotAnalysis.CARDINALITY, DdotAnalysis.CARDINALITY_VALUE -> "cardinality stated at";
            case DdotAnalysis.CASE_VARIANT -> "other spelling at";
            case DdotAnalysis.RELATION_ON_BOTH_SIDES -> "also placed at";
            default -> "see";
        };
    }

    private static String goToFamily(String ruleId) {
        return switch (ruleId) {
            case DdotAnalysis.CARDINALITY, DdotAnalysis.CARDINALITY_VALUE -> "Go to cardinality statement";
            case DdotAnalysis.RELATION_ON_BOTH_SIDES -> "Go to the other statement placing the relation";
            default -> "Go to related statement";
        };
    }

    /** Alt+Enter: jump to a place the finding names, such as where a violated bound is stated. */
    private record GoToStatement(Location loc, String label, String family) implements IntentionAction {
        @Override
        public @NotNull String getText() {
            return family + " (" + label + ")";
        }

        @Override
        public @NotNull String getFamilyName() {
            return family;
        }

        @Override
        public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
            return true;
        }

        @Override
        public void invoke(@NotNull Project project, Editor editor, PsiFile file) {
            DdotGoto.open(project, loc);
        }

        @Override
        public @NotNull IntentionPreviewInfo generatePreview(@NotNull Project project, @NotNull Editor editor,
                                                             @NotNull PsiFile file) {
            return IntentionPreviewInfo.EMPTY;
        }

        @Override
        public boolean startInWriteAction() {
            return false;
        }
    }

    /** Alt+Enter on a letter-case variant: rename every case variant of {@code spelling} to exactly it. */
    private record UnifySpelling(String spelling) implements IntentionAction {
        @Override
        public @NotNull String getText() {
            return "Use '" + spelling + "' everywhere (rename all case variants)";
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Unify letter case";
        }

        @Override
        public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
            return true;
        }

        @Override
        public void invoke(@NotNull Project project, Editor editor, PsiFile file) {
            new DdotRenameProcessor(project, null, spelling, spelling, true).run();
        }

        @Override
        public @NotNull IntentionPreviewInfo generatePreview(@NotNull Project project, @NotNull Editor editor,
                                                             @NotNull PsiFile file) {
            return IntentionPreviewInfo.EMPTY;
        }

        @Override
        public boolean startInWriteAction() {
            return false;
        }
    }
}
