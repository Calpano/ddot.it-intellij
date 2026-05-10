package com.calpano.ddot.injection;

import com.calpano.ddot.DdotIcons;
import com.calpano.ddot.DdotLanguage;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.lang.Language;
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
 *
 * <p>We deliberately do <em>not</em> override {@code getInfoStringForLanguage}:
 * it is marked {@code @ApiStatus.Internal}, and Marketplace blocks plugins that
 * use internal APIs. The interface's default implementation returns {@code null},
 * which is fine — that hook is consulted only when the IDE wants to programmatically
 * insert a fence for a language, not in any user-visible flow we exercise.
 */
public final class DdotMarkdownFenceProvider implements CodeFenceLanguageProvider {

    private static final Set<String> ALIASES = Set.of("ddot", "ddot.it", "ddotit");
    private static final String CANONICAL = "ddot";

    @Override
    public @Nullable Language getLanguageByInfoString(@NotNull String infoString) {
        return ALIASES.contains(infoString.toLowerCase(Locale.ROOT)) ? DdotLanguage.INSTANCE : null;
    }

    @Override
    public @NotNull List<LookupElement> getCompletionVariantsForInfoString(@NotNull CompletionParameters parameters) {
        return List.of(
                LookupElementBuilder.create(CANONICAL).withIcon(DdotIcons.FILE).withTypeText("ddot.it"),
                LookupElementBuilder.create("ddot.it").withIcon(DdotIcons.FILE).withTypeText("ddot.it"));
    }
}
