package com.calpano.ddot.formatter;

import com.calpano.ddot.DdotFileType;
import com.intellij.formatting.service.AsyncDocumentFormattingService;
import com.intellij.formatting.service.AsyncFormattingRequest;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Set;

/**
 * Wires the pure-string {@link DdotLintFormatter} — i.e. the shared linter's FIX path — into IntelliJ's standard
 * "Reformat Code" action via the asynchronous formatter API.
 */
public final class DdotFormattingService extends AsyncDocumentFormattingService {

    private static final Set<Feature> FEATURES = EnumSet.noneOf(Feature.class);

    @Override
    public boolean canFormat(@NotNull PsiFile file) {
        return file.getFileType() == DdotFileType.INSTANCE;
    }

    @Override
    public @NotNull Set<Feature> getFeatures() {
        return FEATURES;
    }

    @Override
    protected @Nullable FormattingTask createFormattingTask(@NotNull AsyncFormattingRequest request) {
        return new FormattingTask() {
            @Override
            public boolean cancel() {
                return false;
            }

            @Override
            public boolean isRunUnderProgress() {
                return false;
            }

            @Override
            public void run() {
                String input = request.getDocumentText();
                String output = DdotLintFormatter.formatDocument(input);
                request.onTextReady(output);
            }
        };
    }

    @Override
    protected @NotNull String getName() {
        return "ddot.it formatter";
    }

    @Override
    protected @NotNull String getNotificationGroupId() {
        return "ddot.it";
    }

}
