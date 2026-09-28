package org.dromara.common.web.handler;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("local")
class GlobalExceptionHandlerTest {
    @Test
    void businessErrorsKeepActionableMessagesWithWarningCode() {
        var response = new GlobalExceptionHandler().handleServiceException(new ServiceException("请选择图片类目"), null);
        assertEquals(601, response.getCode());
        assertEquals("请选择图片类目", response.getMsg());
    }
}
