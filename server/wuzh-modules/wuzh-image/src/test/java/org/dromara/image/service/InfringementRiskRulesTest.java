package org.dromara.image.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("local")
class InfringementRiskRulesTest {
    @Test
    void readsHistoricalCaseNumbers() {
        assertEquals(List.of("1:26-cv-12345"), InfringementRiskRules.cases("[\"1:26-cv-12345\"]"));
        assertEquals(List.of(), InfringementRiskRules.cases("invalid"));
    }
}
