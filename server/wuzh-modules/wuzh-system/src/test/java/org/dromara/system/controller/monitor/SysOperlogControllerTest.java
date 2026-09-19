package org.dromara.system.controller.monitor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.domain.bo.SysOperLogBo;
import org.dromara.system.service.ISysOperLogService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
@Tag("local")
class SysOperlogControllerTest {
    @Test void ordinaryUserCannotReadOperationLogs() {
        ISysOperLogService service = mock(ISysOperLogService.class);
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::isAdmin).thenReturn(false);
            assertThrows(ServiceException.class, () -> new SysOperlogController(service).recent(null));
        }
        verifyNoInteractions(service);
    }
    @Test void administratorCanFilterRecentLogsByUserName() {
        ISysOperLogService service = mock(ISysOperLogService.class);
        when(service.selectPageOperLogList(any(), any())).thenReturn(PageResult.build(List.of(), 0));
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::isAdmin).thenReturn(true);
            new SysOperlogController(service).recent("tester");
        }
        ArgumentCaptor<SysOperLogBo> filter = ArgumentCaptor.forClass(SysOperLogBo.class);
        ArgumentCaptor<PageQuery> page = ArgumentCaptor.forClass(PageQuery.class);
        verify(service).selectPageOperLogList(filter.capture(), page.capture());
        assertNull(filter.getValue().getUserId()); assertEquals("tester", filter.getValue().getOperName());
        assertEquals(1, page.getValue().getPageNum()); assertEquals(8, page.getValue().getPageSize());
    }
}
