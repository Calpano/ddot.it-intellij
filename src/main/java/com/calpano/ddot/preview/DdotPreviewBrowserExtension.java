package com.calpano.ddot.preview;

import com.intellij.openapi.diagnostic.Logger;
import org.intellij.plugins.markdown.extensions.MarkdownBrowserPreviewExtension;
import org.intellij.plugins.markdown.ui.preview.MarkdownHtmlPanel;
import org.intellij.plugins.markdown.ui.preview.ResourceProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

/**
 * Adds a stylesheet + JS to the Markdown preview that re-classes ddot fence
 * content with role-aware spans (subject / predicate / object / metadata /
 * command), matching what users see in the editor.
 *
 * <p>This is the public-API replacement for the previous
 * {@code CodeFenceGeneratingProvider} integration, which Marketplace blocked
 * because that interface is {@code @ApiStatus.Internal}. The flow now:
 *
 * <ol>
 *   <li>The Markdown plugin's default fence renderer emits
 *       {@code <pre class="code-fence"><code class="language-ddot">…raw…</code></pre>}.</li>
 *   <li>This extension injects {@code ddot-preview.css} (role colors) and
 *       {@code ddot-preview.js} (DOM walker that re-implements
 *       {@link DdotMarkdownPreviewRenderer#body} on the rendered HTML).</li>
 *   <li>On every preview reload, the JS re-runs and replaces the inner HTML
 *       of every {@code code[class*="language-ddot"]} block with role-classed
 *       spans.</li>
 * </ol>
 *
 * <p>Trade-off vs. the old in-Java path: scroll sync's per-line
 * {@code data-source-line} markers are lost inside ddot fences (we replace the
 * inner HTML wholesale). Same as before — the old generator also discarded
 * those — so no regression. Theme integration is also gone (CSS can't read
 * {@code EditorColorsScheme}); the colors in {@code ddot-preview.css} are the
 * same hex defaults the old {@code styleBlock()} used as fallback.
 */
public final class DdotPreviewBrowserExtension implements MarkdownBrowserPreviewExtension, ResourceProvider {

    private static final Logger LOG = Logger.getInstance(DdotPreviewBrowserExtension.class);

    private static final String STYLE_NAME  = "ddot-preview.css";
    private static final String SCRIPT_NAME = "ddot-preview.js";
    private static final String CLASSPATH_BASE = "/preview/";
    private static final Set<String> RESOURCES = Set.of(STYLE_NAME, SCRIPT_NAME);

    public static final class Provider implements MarkdownBrowserPreviewExtension.Provider {
        @Override
        public @Nullable MarkdownBrowserPreviewExtension createBrowserExtension(@NotNull MarkdownHtmlPanel panel) {
            return new DdotPreviewBrowserExtension();
        }
    }

    @Override
    public @NotNull List<String> getStyles() {
        return List.of(STYLE_NAME);
    }

    @Override
    public @NotNull List<String> getScripts() {
        return List.of(SCRIPT_NAME);
    }

    @Override
    public @NotNull ResourceProvider getResourceProvider() {
        return this;
    }

    @Override
    public boolean canProvide(@NotNull String resourceName) {
        return RESOURCES.contains(resourceName);
    }

    @Override
    public @Nullable Resource loadResource(@NotNull String resourceName) {
        if (!RESOURCES.contains(resourceName)) return null;
        String contentType = resourceName.endsWith(".css") ? "text/css" : "application/javascript";
        try (InputStream in = getClass().getResourceAsStream(CLASSPATH_BASE + resourceName)) {
            if (in == null) {
                LOG.warn("ddot preview resource not found on classpath: " + CLASSPATH_BASE + resourceName);
                return null;
            }
            return new Resource(in.readAllBytes(), contentType);
        } catch (IOException e) {
            LOG.warn("Failed to read ddot preview resource " + resourceName, e);
            return null;
        }
    }

    @Override
    public void dispose() { }
}
