package com.calpano.ddot.parser;

import com.calpano.ddot.DdotLanguage;
import com.calpano.ddot.lexer.DdotLexer;
import com.calpano.ddot.psi.DdotEntity;
import com.calpano.ddot.psi.DdotFile;
import com.calpano.ddot.psi.DdotLine;
import com.calpano.ddot.psi.DdotMetadata;
import com.calpano.ddot.psi.DdotTypes;
import com.intellij.lang.ASTNode;
import com.intellij.lang.ParserDefinition;
import com.intellij.lang.PsiParser;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.project.Project;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.psi.tree.TokenSet;
import org.jetbrains.annotations.NotNull;

public final class DdotParserDefinition implements ParserDefinition {
    public static final IFileElementType FILE = new IFileElementType(DdotLanguage.INSTANCE);

    private static final TokenSet WHITE_SPACES = TokenSet.create(TokenType.WHITE_SPACE);

    @Override
    public @NotNull Lexer createLexer(Project project) {
        return new DdotLexer();
    }

    @Override
    public @NotNull PsiParser createParser(Project project) {
        return new DdotParser();
    }

    @Override
    public @NotNull IFileElementType getFileNodeType() {
        return FILE;
    }

    @Override
    public @NotNull TokenSet getWhitespaceTokens() {
        return WHITE_SPACES;
    }

    @Override
    public @NotNull TokenSet getCommentTokens() {
        return DdotTypes.COMMENTS;
    }

    @Override
    public @NotNull TokenSet getStringLiteralElements() {
        return DdotTypes.STRINGS;
    }

    @Override
    public @NotNull PsiElement createElement(ASTNode node) {
        var type = node.getElementType();
        if (type == DdotTypes.LINE) return new DdotLine(node);
        if (type == DdotTypes.ENTITY) return new DdotEntity(node);
        if (type == DdotTypes.METADATA) return new DdotMetadata(node);
        throw new IllegalStateException("Unknown element type: " + type);
    }

    @Override
    public @NotNull PsiFile createFile(@NotNull FileViewProvider viewProvider) {
        return new DdotFile(viewProvider);
    }
}
