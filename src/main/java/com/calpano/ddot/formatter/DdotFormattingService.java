package com.calpano.ddot.formatter;

import com.calpano.ddot.DdotFileType;
import com.intellij.formatting.service.AsyncDocumentFormattingService;
import com.intellij.formatting.service.AsyncFormattingRequest;
import com.intellij.formatting.service.FormattingService;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Set;

/**
 * Wires the pure-string {@link DdotFormatter} into IntelliJ's standard
 * "Reformat Code" action via the asynchronous formatter API.
 */
public final class DdotFormattingService extends AsyncDocumentFormattingService {

    private static final Set<Feature> FEATURES = EnumSet.noneOf(Feature.class);

    @Override
    public @NotNull Set<Feature> getFeatures() {
        return FEATURES;
    }

    @Override
    public boolean canFormat(@NotNull PsiFile file) {
        return file.getFileType() == DdotFileType.INSTANCE;
    }

    @Override
    protected @Nullable FormattingTask createFormattingTask(@NotNull AsyncFormattingRequest request) {
        return new FormattingTask() {
            @Override
            public void run() {
                String input = request.getDocumentText();
                String output = DdotFormatter.formatDocument(input);
                request.onTextReady(output);
            }

            @Override
            public boolean cancel() {
                return false;
            }

            @Override
            public boolean isRunUnderProgress() {
                return false;
            }
        };
    }

    @Override
    protected @NotNull String getNotificationGroupId() {
        return "ddot.it";
    }

    @Override
    protected @NotNull String getName() {
        return "ddot.it formatter";
    }

    /**
     * Some IDE call sites still ask via {@link FormattingService#getFileTypes()}.
     */
    public Set<FileType> getFileTypes() {
        return Set.of(DdotFileType.INSTANCE);
    }
}
