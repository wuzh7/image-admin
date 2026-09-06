package org.dromara.system.controller.monitor;

import cn.dev33.satoken.stp.StpUtil;
import org.dromara.common.core.constant.SystemConstants;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.domain.bo.SysOperLogBo;
import org.dromara.system.domain.vo.SysOperLogVo;
import org.dromara.system.service.ISysOperLogService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("local")
class SysOperlogControllerTest {

    @Test
    void recentScopesOrdinaryUserToSelf() {
        ISysOperLogService service = mock(ISysOperLogService.class);
        when(service.selectPageOperLogList(any(), any())).thenReturn(PageResult.build(List.of(), 0));
        SysOperlogController controller = new SysOperlogController(service);

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class);
             MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            stp.when(() -> StpUtil.hasRole(SystemConstants.SUPER_ADMIN_ROLE_KEY)).thenReturn(false);
            login.when(LoginHelper::getUserId).thenReturn(88L);

            controller.recent();
        }

        ArgumentCaptor<SysOperLogBo> filter = ArgumentCaptor.forClass(SysOperLogBo.class);
        ArgumentCaptor<PageQuery> page = ArgumentCaptor.forClass(PageQuery.class);
        verify(service).selectPageOperLogList(filter.capture(), page.capture());
        assertEquals(88L, filter.getValue().getUserId());
        assertEquals(1, page.getValue().getPageNum());
        assertEquals(8, page.getValue().getPageSize());
    }

    @Test
    void recentDoesNotScopeSuperAdmin() {
        ISysOperLogService service = mock(ISysOperLogService.class);
        when(service.selectPageOperLogList(any(), any())).thenReturn(PageResult.build(List.of(), 0));
        SysOperlogController controller = new SysOperlogController(service);

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(() -> StpUtil.hasRole(SystemConstants.SUPER_ADMIN_ROLE_KEY)).thenReturn(true);
            controller.recent();
        }

        ArgumentCaptor<SysOperLogBo> filter = ArgumentCaptor.forClass(SysOperLogBo.class);
        verify(service).selectPageOperLogList(filter.capture(), any());
        assertNull(filter.getValue().getUserId());
    }
}
