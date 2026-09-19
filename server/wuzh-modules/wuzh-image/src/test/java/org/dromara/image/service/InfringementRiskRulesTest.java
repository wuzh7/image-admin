package org.dromara.image.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("local")
class InfringementRiskRulesTest {

    @Test
    void looksUpLowRiskWithoutARiskReason() {
        assertTrue(InfringementRiskRules.needsCaseLookup(response("LOW", null)));
        assertTrue(InfringementRiskRules.needsCaseLookup(response("HIGH", "疑似既有作品")));
        assertFalse(InfringementRiskRules.needsCaseLookup(response("HIGH", null)));
    }

    private static GeminiGatewayClient.GatewayResponse response(String riskLevel, String riskReason) {
        return new GeminiGatewayClient.GatewayResponse("request", riskLevel, riskReason, "description",
            "raw", "model", "prompt", false, 0);
    }
}
