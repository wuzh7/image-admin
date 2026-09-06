package org.dromara.image.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tags({@Tag("dev"), @Tag("local")})
class ImageAspectRatioTest {

    @Test
    void classifiesImageDimensionsIntoCommonBusinessAspectRatios() throws Exception {
        Class<?> type = Class.forName("org.dromara.image.domain.ImageAspectRatio");
        Method format = type.getMethod("format", int.class, int.class);

        assertEquals("4:5", format.invoke(null, 1080, 1350));
        assertEquals("1:1", format.invoke(null, 1200, 1200));
        assertEquals("16:9", format.invoke(null, 1920, 1080));
        assertEquals("16:9", format.invoke(null, 183, 104));
        assertEquals("4:5", format.invoke(null, 198, 245));
        assertEquals("其他", format.invoke(null, 7, 5));
    }
}
