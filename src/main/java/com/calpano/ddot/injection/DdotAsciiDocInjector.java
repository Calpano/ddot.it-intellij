package com.calpano.ddot.injection;

import com.calpano.ddot.DdotLanguage;
import com.intellij.lang.injection.MultiHostInjector;
import com.intellij.lang.injection.MultiHostRegistrar;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLanguageInjectionHost;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Injects {@link DdotLanguage} into AsciiDoc listing blocks tagged with
 * {@code [source,ddot]} (or {@code [ddot]}, {@code [source,ddot.it]},
 * {@code [source,ddotit]}). The asciidoctor plugin
 * (<a href="https://plugins.jetbrains.com/plugin/7391-asciidoc">org.asciidoctor.intellij.asciidoc</a>)
 * exposes listing blocks as {@code PsiLanguageInjectionHost}s; we identify them
 * by class name to avoid a compile-time dependency on the third-party plugin.
 *
 * <p>This injector is registered from {@code META-INF/ddot-asciidoc.xml}, which
 * is loaded only when the asciidoctor plugin is installed.
 */
public final class DdotAsciiDocInjector implements MultiHostInjector {

    private static final Set<String> ALIASES = Set.of("ddot", "ddot.it", "ddotit");

    private static final String LISTING_FQN = "org.asciidoc.intellij.psi.AsciiDocListing";
    private static final String ASCIIDOC_LANGUAGE_ID = "AsciiDoc";

    /** {@code [source,ddot]} or {@code [source , ddot.it]} or {@code [ddot]}. */
    private static final Pattern HEADER = Pattern.compile(
            "^\\[\\s*(?:source\\s*,\\s*)?([A-Za-z][A-Za-z0-9._-]*)\\s*]\\s*$");

    /** A listing-block delimiter line: 4+ identical characters from a small set. */
    private static final Pattern DELIM = Pattern.compile("^([-=.~_*+])\\1{3,}\\s*$");

    @Override
    public @NotNull List<? extends Class<? extends PsiElement>> elementsToInjectIn() {
        return List.of(PsiLanguageInjectionHost.class);
    }

    @Override
    public void getLanguagesToInject(@NotNull MultiHostRegistrar registrar, @NotNull PsiElement context) {
        if (!(context instanceof PsiLanguageInjectionHost host) || !host.isValidHost()) return;
        if (!ASCIIDOC_LANGUAGE_ID.equals(context.getContainingFile().getLanguage().getID())) return;
        if (!LISTING_FQN.equals(context.getClass().getName())) return;

        String text = context.getText();
        TextRange body = findContentRange(text);
        if (body == null) return;

        registrar.startInjecting(DdotLanguage.INSTANCE)
                .addPlace(null, null, host, body)
                .doneInjecting();
    }

    /**
     * Returns the range of injectable content within the listing block text, or
     * {@code null} if the header doesn't carry a ddot label or no matching
     * delimiter pair is found.
     */
    static TextRange findContentRange(String text) {
        String label = null;
        int searchOffset = 0;
        int delimOpenStart = -1;
        int delimOpenEnd = -1;
        char delimChar = 0;
        int delimRunLen = 0;

        // Walk lines until we either hit the opening delimiter or run out of header.
        while (searchOffset <= text.length()) {
            int eol = text.indexOf('\n', searchOffset);
            int lineEnd = eol < 0 ? text.length() : eol;
            String line = text.substring(searchOffset, lineEnd);
            String trimmed = line.stripTrailing();

            Matcher dm = DELIM.matcher(trimmed);
            if (dm.matches()) {
                delimOpenStart = searchOffset;
                delimOpenEnd = lineEnd + (eol < 0 ? 0 : 1);
                delimChar = trimmed.charAt(0);
                delimRunLen = trimmed.length();
                break;
            }

            Matcher hm = HEADER.matcher(trimmed);
            if (hm.matches()) {
                label = hm.group(1).toLowerCase(Locale.ROOT);
            }

            if (eol < 0) break;
            searchOffset = eol + 1;
        }

        if (label == null || !ALIASES.contains(label)) return null;
        if (delimOpenStart < 0) return null;

        // Find the matching closing delimiter (same char, length >= opening run).
        int cursor = delimOpenEnd;
        int contentStart = cursor;
        while (cursor < text.length()) {
            int eol = text.indexOf('\n', cursor);
            int lineEnd = eol < 0 ? text.length() : eol;
            String line = text.substring(cursor, lineEnd).stripTrailing();
            if (line.length() >= delimRunLen && allSame(line, delimChar)) {
                int contentEnd = cursor;
                if (contentEnd > contentStart && text.charAt(contentEnd - 1) == '\n') contentEnd--;
                if (contentEnd < contentStart) contentEnd = contentStart;
                return new TextRange(contentStart, contentEnd);
            }
            if (eol < 0) break;
            cursor = eol + 1;
        }
        return null;
    }

    private static boolean allSame(String s, char c) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) != c) return false;
        return !s.isEmpty();
    }
}
