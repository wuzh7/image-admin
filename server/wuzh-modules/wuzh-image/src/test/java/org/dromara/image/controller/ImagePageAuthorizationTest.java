package org.dromara.image.controller;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.filter.SaTokenContextFilterForJakartaServlet;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import org.dromara.image.service.ImageDataLock;
import org.dromara.image.service.ImageLibraryLock;
import org.dromara.image.service.ImageResetService;
import org.dromara.image.service.ImageService;
import org.dromara.image.service.ImageUploadService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import jakarta.servlet.ServletException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@Tag("local")
class ImagePageAuthorizationTest {

    @Test
    void exporterReceivesSignedArchiveLinkWithoutProxyingZip() throws Exception {
        StpInterface original = SaManager.getStpInterface();
        try {
            SaManager.setStpInterface(new StpInterface() {
                @Override public List<String> getPermissionList(Object loginId, String loginType) {
                    return List.of("image:dedup:export");
                }
                @Override public List<String> getRoleList(Object loginId, String loginType) {
                    return List.of();
                }
            });
            ImageService service = mock(ImageService.class);
            when(service.exportApprovedLink(any())).thenReturn("https://oss.example/exports/library.zip?signature=short");
            var controller = new ImageController(service, mock(ImageUploadService.class),
                new ImageDataLock(), mock(ImageResetService.class), mock(ImageLibraryLock.class));
            var mvc = standaloneSetup(controller, new TestLoginController()).addInterceptors(new SaInterceptor())
                .addFilters(new SaTokenContextFilterForJakartaServlet()).build();
            String token = mvc.perform(post("/test-login")).andReturn().getResponse().getContentAsString();

            mvc.perform(get("/image/storage").header(SaManager.getConfig().getTokenName(), token))
                .andExpect(status().isOk());
            mvc.perform(post("/images/export-link").header(SaManager.getConfig().getTokenName(), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("https://oss.example/exports/library.zip?signature=short"));
        } finally {
            SaManager.setStpInterface(original);
        }
    }

    @Test
    void libraryReaderCanLoadPageWithItsMenuPermission() throws Exception {
        StpInterface original = SaManager.getStpInterface();
        try {
            SaManager.setStpInterface(new StpInterface() {
                @Override public List<String> getPermissionList(Object loginId, String loginType) {
                    return List.of("image:dedup:query");
                }
                @Override public List<String> getRoleList(Object loginId, String loginType) {
                    return List.of();
                }
            });
            var controller = new ImageController(mock(ImageService.class), mock(ImageUploadService.class),
                mock(ImageDataLock.class), mock(ImageResetService.class), mock(ImageLibraryLock.class));
            var mvc = standaloneSetup(controller, new TestLoginController()).addInterceptors(new SaInterceptor())
                .addFilters(new SaTokenContextFilterForJakartaServlet()).build();
            String token = mvc.perform(post("/test-login")).andReturn().getResponse().getContentAsString();

            mvc.perform(get("/images").header(SaManager.getConfig().getTokenName(), token))
                .andExpect(status().isOk());
            mvc.perform(get("/image/health").header(SaManager.getConfig().getTokenName(), token))
                .andExpect(status().isOk());
            mvc.perform(get("/images/aspect-ratios").header(SaManager.getConfig().getTokenName(), token))
                .andExpect(status().isOk());
            ServletException denied = assertThrows(ServletException.class,
                () -> mvc.perform(post("/images/export").header(SaManager.getConfig().getTokenName(), token)));
            assertInstanceOf(NotPermissionException.class, denied.getCause());
            ServletException linkDenied = assertThrows(ServletException.class,
                () -> mvc.perform(post("/images/export-link").header(SaManager.getConfig().getTokenName(), token)));
            assertInstanceOf(NotPermissionException.class, linkDenied.getCause());
        } finally {
            SaManager.setStpInterface(original);
        }
    }

    @RestController
    static class TestLoginController {
        @PostMapping("/test-login")
        String login() {
            StpUtil.login("library-reader");
            return StpUtil.getTokenValue();
        }
    }
}
