package com.calpano.ddot.documentation;

import com.calpano.ddot.completion.DdotKgraphService;
import com.calpano.ddot.psi.DdotEntity;
import com.calpano.ddot.psi.DdotPsiUtil;
import com.intellij.lang.documentation.DocumentationMarkup;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import it.ddot.core.token.Commands;
import it.ddot.core.token.PreCommand;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Function;

/**
 * The entity popup: what the project knowledge graph states about an entity —
 * its relations and, inverse, the relations pointing at it — as HTML whose
 * every item links (via {@link DdotTripleNavigator}) to where the triple is
 * stated. Shared by the hover documentation and the Alt+Enter action.
 */
final class DdotEntityFacts {

    /** Entities shown per relation before the list is cut short. */
    private static final int MAX_ENTITIES = 30;

    private DdotEntityFacts() {}

    /** The entity under {@code offset}, or just before it (caret at the end of a word). */
    static @Nullable DdotEntity entityAt(@NotNull PsiFile file, int offset) {
        for (int o : new int[]{offset, offset - 1}) {
            if (o < 0) continue;
            PsiElement leaf = file.findElementAt(o);
            DdotEntity entity = leaf == null ? null : PsiTreeUtil.getParentOfType(leaf, DdotEntity.class, false);
            if (entity != null) return entity;
        }
        return null;
    }

    /**
     * Popup HTML for {@code entity}. On a triple line it is shown even when the graph
     * states nothing about the entity, so hovering always answers; {@code null} for a
     * word in free text (a title, a note) the graph knows nothing about.
     */
    static @Nullable String html(@NotNull DdotEntity entity) {
        String name = graphId(entity);
        if (name == null) return null;
        DdotKgraphService.EntityFacts facts = DdotKgraphService.getInstance(entity.getProject()).facts(name);

        StringBuilder sb = new StringBuilder();
        if (facts.isEmpty() && !DdotPsiUtil.isOnTripleLine(entity)) return null;
        sb.append(DocumentationMarkup.DEFINITION_START).append("<b>").append(escape(entity.getName())).append("</b>")
                .append(" &nbsp;").append(a(DdotTripleNavigator.renameLink(entity.getName()), "Rename…"))
                .append(DocumentationMarkup.DEFINITION_END)
                .append(DocumentationMarkup.CONTENT_START);
        if (facts.isEmpty()) {
            sb.append("<i>Nothing is stated about it in the project.</i>");
            return sb.append(DocumentationMarkup.CONTENT_END).toString();
        }
        sb.append("<ul>");
        for (DdotKgraphService.RelationGroup g : facts.outgoing()) {
            sb.append("<li>").append(a(DdotTripleNavigator.link(name, g.relation(), null), g.relation()))
                    .append(" &rarr; ");
            appendEntities(sb, g, other -> DdotTripleNavigator.link(name, g.relation(), other));
            sb.append("</li>");
        }
        for (DdotKgraphService.RelationGroup g : facts.incoming()) {
            sb.append("<li>&rarr; ").append(a(DdotTripleNavigator.link(null, g.relation(), name), g.relation()))
                    .append(": ");
            appendEntities(sb, g, other -> DdotTripleNavigator.link(other, g.relation(), name));
            sb.append("</li>");
        }
        // Used as a relation: one line per triple, written as ddot.it.
        List<DdotKgraphService.Pair> uses = facts.asRelation();
        for (int i = 0; i < uses.size() && i < MAX_ENTITIES; i++) {
            DdotKgraphService.Pair use = uses.get(i);
            String href = DdotTripleNavigator.link(use.subject(), name, use.object());
            sb.append("<li>").append(a(href, use.subject())).append(" ..").append(escape(name)).append(".. ")
                    .append(a(href, use.object())).append("</li>");
        }
        if (uses.size() > MAX_ENTITIES) {
            sb.append("<li>… (").append(uses.size() - MAX_ENTITIES).append(" more)</li>");
        }
        return sb.append("</ul>").append(DocumentationMarkup.CONTENT_END).toString();
    }

    private static void appendEntities(StringBuilder sb, DdotKgraphService.RelationGroup g,
                                       Function<String, String> linkTo) {
        List<String> entities = g.entities();
        for (int i = 0; i < entities.size() && i < MAX_ENTITIES; i++) {
            if (i > 0) sb.append(", ");
            sb.append(a(linkTo.apply(entities.get(i)), entities.get(i)));
        }
        if (entities.size() > MAX_ENTITIES) {
            sb.append(", … (").append(entities.size() - MAX_ENTITIES).append(" more)");
        }
    }

    /**
     * The entity's node id in the graph. A {@code ddot.it/this} command stands for the
     * document itself, which the graph keys by the (host) file URL.
     */
    private static @Nullable String graphId(@NotNull DdotEntity entity) {
        String name = entity.getName();
        if (name == null || name.isEmpty()) return null;
        if (!PreCommand.THIS_CMD.matcher(Commands.normalizeCommandToken(name)).matches()) return name;
        PsiFile host = InjectedLanguageManager.getInstance(entity.getProject()).getTopLevelFile(entity);
        VirtualFile file = host != null ? host.getVirtualFile() : null;
        return file != null ? file.getUrl() : null;
    }

    private static String a(String href, String text) {
        return "<a href=\"" + escape(href) + "\">" + escape(text) + "</a>";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
