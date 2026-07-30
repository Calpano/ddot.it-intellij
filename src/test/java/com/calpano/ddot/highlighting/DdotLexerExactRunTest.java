package com.calpano.ddot.highlighting;

import com.calpano.ddot.lexer.DdotLexer;
import com.calpano.ddot.psi.DdotTypes;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Exact-run separator semantics in {@link DdotLexer} (Parse Spec,
 * "Tokenization rule"): a maximal run of a symbol character is a separator
 * only at its exact special length — 2 or 4 dots, 2 commas, 2 semicolons.
 * Everything else ({@code ...}, {@code .....}, {@code ,,,}, a lone {@code .})
 * is ordinary word text.
 */
public class DdotLexerExactRunTest {

    private static List<String> lex(String text) {
        DdotLexer lexer = new DdotLexer();
        lexer.start(text, 0, text.length(), 0);
        List<String> out = new ArrayList<>();
        IElementType t;
        while ((t = lexer.getTokenType()) != null) {
            String name =
                    t == DdotTypes.WORD ? "W" :
                    t == DdotTypes.DOT_DOT ? "DT2" :
                    t == DdotTypes.DOT_DOT_DOT_DOT ? "DT4" :
                    t == DdotTypes.COMMA_COMMA ? "CM2" :
                    t == DdotTypes.SEMI_SEMI ? "SC2" :
                    t == TokenType.WHITE_SPACE ? "_" :
                    t == DdotTypes.NEWLINE ? "NL" : t.toString();
            out.add(name + "(" + lexer.getTokenText() + ")");
            lexer.advance();
        }
        return out;
    }

    @Test
    public void exactlyTwoDotsIsSeparator() {
        assertEquals(List.of("W(a)", "DT2(..)", "W(b)"), lex("a..b"));
    }

    @Test
    public void threeDotsIsText() {
        assertEquals(List.of("W(Wait...)", "_( )", "W(what?)"), lex("Wait... what?"));
    }

    @Test
    public void fourDotsIsUntypedOperator() {
        assertEquals(List.of("W(a)", "DT4(....)", "W(b)"), lex("a....b"));
    }

    @Test
    public void fiveDotsIsText() {
        assertEquals(List.of("W(a.....b)"), lex("a.....b"));
    }

    @Test
    public void singleDotsStayInWords() {
        assertEquals(List.of("W(U.S.A.)"), lex("U.S.A."));
        assertEquals(List.of("W(Node.js)"), lex("Node.js"));
    }

    @Test
    public void exactlyTwoCommasIsMetaDelimiter() {
        assertEquals(List.of("W(o)", "_( )", "CM2(,,)"), lex("o ,,"));
    }

    @Test
    public void threeCommasIsText() {
        assertEquals(List.of("W(a,,,b)"), lex("a,,,b"));
    }

    @Test
    public void exactlyTwoSemicolonsIsMetaSeparator() {
        assertEquals(List.of("W(x)", "_( )", "SC2(;;)", "_( )", "W(y)"), lex("x ;; y"));
    }

    @Test
    public void threeSemicolonsIsText() {
        assertEquals(List.of("W(x;;;y)"), lex("x;;;y"));
    }

    @Test
    public void spacedUntypedOperatorLexesAsTwoDoubleDots() {
        // `.. ..` is ONE operator at the parse level (QuadDot); the lexer may
        // deliver it as DT2 WS DT2 — role assignment then counts it as
        // subject → object, same as DT4.
        assertEquals(List.of("W(a)", "_( )", "DT2(..)", "_( )", "DT2(..)", "_( )", "W(b)"),
                lex("a .. .. b"));
    }
}
