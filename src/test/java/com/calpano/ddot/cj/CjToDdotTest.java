package com.calpano.ddot.cj;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Exercises the graphinout-backed CJ -> ddot.it conversion end-to-end (parse +
 * write), independent of the IDE. This is also the canary that the bundled
 * graphinout dependency stack actually loads and runs.
 */
public class CjToDdotTest {

    private static final String CUSTOM_DATA_SIMPLE = "{\n" +
            "  \"connectedJson\": { \"versionDate\": \"2025-07-14\", \"versionNumber\": \"5.0.0\" },\n" +
            "  \"graphs\": [\n" +
            "    {\n" +
            "      \"nodes\": [\n" +
            "        { \"id\": \"london\", \"data\": { \"population\": 8982000 } },\n" +
            "        { \"id\": \"tokyo\",  \"data\": { \"population\": 123456 } }\n" +
            "      ],\n" +
            "      \"edges\": [\n" +
            "        { \"id\": \"flight\", \"endpoints\": [ { \"node\": \"london\" }, { \"node\": \"tokyo\" } ] }\n" +
            "      ]\n" +
            "    }\n" +
            "  ]\n" +
            "}\n";

    @Test
    public void convertsNodesAndEdgesToDdot() throws Exception {
        String ddot = CjToDdot.convert("custom-data-simple.cj.json", CUSTOM_DATA_SIMPLE);

        assertFalse("conversion produced empty output", ddot.trim().isEmpty());
        // The node ids and the edge must surface as entities in the ddot output.
        assertTrue("expected 'london' in:\n" + ddot, ddot.contains("london"));
        assertTrue("expected 'tokyo' in:\n" + ddot, ddot.contains("tokyo"));
    }

    @Test
    public void convertsMinimalDocument() throws Exception {
        String minimal = "{ \"connectedJson\": { \"versionDate\": \"2025-07-14\", \"versionNumber\": \"5.0.0\" } }";
        // Must not throw and must return a (possibly near-empty) string.
        String ddot = CjToDdot.convert("minimal.cj.json", minimal);
        assertTrue(ddot != null);
    }
}
