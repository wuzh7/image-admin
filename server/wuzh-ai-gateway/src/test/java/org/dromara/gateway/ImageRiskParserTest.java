package org.dromara.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("local")
class ImageRiskParserTest {
    @Test
    void relayFinalAllowsCaseConfirmedHighWithoutRiskReason() {
        var result = new ImageRiskParser().parseRelayFinal("""
            {"riskLevel":"HIGH","riskReason":null,"descriptionKeywords":"flower art",
             "subjects":[],"scenes":[],"themes":[],"visualStyles":[],"colors":[],
             "CaseNumber":["1:26-cv-12345"]}
            """);

        assertEquals("HIGH", result.riskLevel());
        assertEquals(null, result.riskReason());
        assertEquals(List.of("1:26-cv-12345"), result.caseNumbers());
    }

    private final ImageRiskParser parser = new ImageRiskParser();

    @Test
    void parsesStructuredHighRiskResponse() {
        var result = parser.parse("""
            {"riskLevel":"HIGH","riskReason":"包含知名动漫角色版权元素","imageDescription":"Anime character graphic illustration"}
            """);
        assertEquals("HIGH", result.riskLevel());
        assertEquals("包含知名动漫角色版权元素", result.riskReason());
        assertEquals("Anime character graphic illustration", result.imageDescription());
    }

    @Test
    void truncatesLongEnglishDescriptionAtWordBoundary() {
        var result = parser.parse("""
            {"riskLevel":"LOW","riskReason":null,"imageDescription":"A detailed watercolor illustration of a black cat in moonlight"}
            """);
        assertEquals("LOW", result.riskLevel());
        assertEquals("A detailed watercolor illustration of a black cat", result.imageDescription());
    }

    @Test
    void preservesLowRiskReasonForCaseLookupCondition() {
        var result = parser.parse("""
            {"riskLevel":"LOW","riskReason":"疑似既有作品","descriptionKeywords":"lion,flowers","CaseNumber":[]}
            """);
        assertEquals("疑似既有作品", result.riskReason());
        assertEquals(List.of(), result.caseNumbers());
    }

    @Test
    void truncatesLongHighRiskReason() {
        String reason = "一二三四五六七八九十".repeat(11);
        var result = parser.parse("""
            {"riskLevel":"HIGH","riskReason":"%s","imageDescription":"Recognizable copyrighted cartoon character"}
            """.formatted(reason));
        assertEquals("一二三四五六七八九十".repeat(10), result.riskReason());
    }

    @Test
    void preservesNeedsReviewAndKeepsVisualTags() {
        var result = parser.parse("""
            {"riskLevel":"NEEDS_REVIEW","riskReason":"可见CCCP文字及既有商业插画疑点","descriptionKeywords":"lioness,red tracksuit,CCCP","subjects":["雌狮","CCCP服装"],"scenes":[],"themes":["苏8054怀旧"],"visualStyles":["水彩插画"],"colors":["红色"]}
            """);

        assertEquals("NEEDS_REVIEW", result.riskLevel());
        assertEquals("可见CCCP文字及既有商业插画疑点", result.riskReason());
        assertEquals("lioness,red tracksuit,CCCP", result.imageDescription());
        assertEquals(List.of("雌狮", "CCCP服装"), result.visualTags().subjects());
        assertEquals(List.of("红色"), result.visualTags().colors());
    }

    @Test
    void parsesLowercaseNeedsReviewAndCaseNumberArray() {
        var result = parser.parse("""
            {"riskLevel":"needs_review","riskReason":"疑似既有艺术作品","descriptionKeywords":"lion,flowers","CaseNumber":[" 1:26-cv-12345 ","","1:26-cv-12345"]}
            """);
        assertEquals("NEEDS_REVIEW", result.riskLevel());
        assertEquals(List.of("1:26-cv-12345"), result.caseNumbers());
        assertEquals(List.of(), parser.parseCases("[]"));
        assertEquals(List.of("1:26-cv-12345"), parser.parseCases("[\"1:26-cv-12345\",\"\"]"));
        assertThrows(GatewayException.class, () -> parser.parseCases("not-json"));
        assertThrows(GatewayException.class, () -> parser.parseCases("[123]"));
    }

    @Test
    void rejectsMissingReasonNonEnglishDescriptionAndInvalidJsonWithExactCodes() {
        assertCode("INVALID_RISK_REASON", """
            {"riskLevel":"HIGH","riskReason":null,"imageDescription":"Copyrighted cartoon character"}
            """);
        assertCode("INVALID_IMAGE_DESCRIPTION", """
            {"riskLevel":"LOW","riskReason":null,"imageDescription":"水彩猫插画"}
            """);
        assertCode("UNPARSABLE_AI_RESPONSE", "not-json");
    }

    private void assertCode(String expected, String raw) {
        GatewayException error = assertThrows(GatewayException.class, () -> parser.parse(raw));
        assertEquals(expected, error.code());
    }
}
