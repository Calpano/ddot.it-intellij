package com.calpano.ddot.completion;

import com.calpano.ddot.DdotFileType;
import com.intellij.codeInsight.AutoPopupController;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two-step auto-popup handshake (mirrors the VS Code original): a document edit that
 * inserts a newline arms a flag; the next caret-change event consumes the flag and,
 * if the cursor is now on a fresh continuation line, schedules a completion popup.
 * <p>setTimeout-style deferral was unreliable in VS Code (the cursor sometimes hadn't
 * moved yet when the timer ran) — the same logic applies here.
 */
public final class DdotAutoPopupRegistrar implements EditorFactoryListener {

    private final Map<Editor, Disposable> disposables = new ConcurrentHashMap<>();

    @Override
    public void editorCreated(@NotNull EditorFactoryEvent event) {
        Editor editor = event.getEditor();
        VirtualFile vf = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (vf == null || vf.getFileType() != DdotFileType.INSTANCE) return;

        Disposable disposable = Disposer.newDisposable("DdotAutoPopupRegistrar");
        disposables.put(editor, disposable);
        Handler handler = new Handler(editor);
        editor.getDocument().addDocumentListener(handler, disposable);
        editor.getCaretModel().addCaretListener(handler, disposable);
    }

    @Override
    public void editorReleased(@NotNull EditorFactoryEvent event) {
        Disposable disposable = disposables.remove(event.getEditor());
        if (disposable != null) Disposer.dispose(disposable);
    }

    private static final class Handler implements DocumentListener, CaretListener {
        private final Editor editor;
        private volatile boolean pendingNewlineCheck;

        Handler(Editor editor) {
            this.editor = editor;
        }

        @Override
        public void documentChanged(@NotNull DocumentEvent event) {
            if (event.getNewFragment().toString().indexOf('\n') >= 0) {
                pendingNewlineCheck = true;
            }
        }

        @Override
        public void caretPositionChanged(@NotNull CaretEvent event) {
            if (!pendingNewlineCheck) return;
            pendingNewlineCheck = false;

            Project project = editor.getProject();
            if (project == null) return;
            Document doc = editor.getDocument();
            int offset = editor.getCaretModel().getOffset();
            int line = doc.getLineNumber(offset);
            int s = doc.getLineStartOffset(line);
            String beforeCursor = doc.getCharsSequence().subSequence(s, offset).toString();
            if (!beforeCursor.isBlank()) return;
            if (!DdotContext.isContinuationContext(doc, line)) return;

            AutoPopupController.getInstance(project).scheduleAutoPopup(editor);
        }
    }
}
