package org.dromara.gateway;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("local")
class ImageRiskParserTest {
    private final ImageRiskParser parser = new ImageRiskParser();

    @Test
    void acceptsOnlyVisualFieldsWithChineseTags() {
        var result = parser.parseVisual("""
            {"descriptionKeywords":"A red flower on white.","subjects":["花卉"],"scenes":[],
             "themes":[],"visualStyles":["3D渲染"],"colors":["红色"]}
            """);
        assertEquals("A red flower on white.", result.imageDescription());
        assertEquals("UNPARSABLE_AI_RESPONSE", code("""
            {"descriptionKeywords":"Flower","subjects":[],"scenes":[],"themes":[],"visualStyles":[],"colors":[],"riskLevel":"LOW"}
            """));
        assertEquals("INVALID_VISUAL_TAG", code("""
            {"descriptionKeywords":"Flower","subjects":["BrandName"],"scenes":[],"themes":[],"visualStyles":[],"colors":[]}
            """));
        assertEquals("INVALID_VISUAL_TAG", code("""
            {"descriptionKeywords":"Flower","subjects":[],"scenes":[],"themes":[],"visualStyles":[],"colors":["红色","蓝色"]}
            """));
        assertEquals("INVALID_IMAGE_DESCRIPTION", code("""
            {"descriptionKeywords":"水彩花卉","subjects":[],"scenes":[],"themes":[],"visualStyles":[],"colors":[]}
            """));
    }

    private String code(String raw) {
        return assertThrows(GatewayException.class, () -> parser.parseVisual(raw)).code();
    }
}
