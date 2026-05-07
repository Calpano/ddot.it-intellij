package com.calpano.ddot.injection;

import com.calpano.ddot.DdotIcons;
import com.calpano.ddot.DdotLanguage;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.lang.Language;
import com.intellij.psi.PsiElement;
import org.intellij.plugins.markdown.injection.CodeFenceLanguageProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Maps Markdown fence info strings to {@link DdotLanguage}, so a fenced block
 * like {@code ```ddot} embeds the ddot.it editor (highlighting, completion,
 * formatting, rename) inside Markdown documents.
 *
 * <p>The Markdown plugin's {@code CodeFenceLanguageGuesser} matches by
 * {@code Language#getID} only — our ID is {@code ddot.it}, so users typing the
 * shorter {@code ddot} need this provider to bridge the gap. We also accept
 * {@code ddotit} for hosts that strip dots from labels.
 */
public final class DdotMarkdownFenceProvider implements CodeFenceLanguageProvider {

    private static final Set<String> ALIASES = Set.of("ddot", "ddot.it", "ddotit");
    private static final String CANONICAL = "ddot";

    @Override
    public @Nullable Language getLanguageByInfoString(@NotNull String infoString) {
        return ALIASES.contains(infoString.toLowerCase(Locale.ROOT)) ? DdotLanguage.INSTANCE : null;
    }

    @Override
    public @Nullable String getInfoStringForLanguage(@NotNull Language language, @NotNull PsiElement element) {
        return language == DdotLanguage.INSTANCE ? CANONICAL : null;
    }

    @Override
    public @NotNull List<LookupElement> getCompletionVariantsForInfoString(@NotNull CompletionParameters parameters) {
        return List.of(
                LookupElementBuilder.create(CANONICAL).withIcon(DdotIcons.FILE).withTypeText("ddot.it"),
                LookupElementBuilder.create("ddot.it").withIcon(DdotIcons.FILE).withTypeText("ddot.it"));
    }
}
