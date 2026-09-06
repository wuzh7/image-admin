package org.dromara.web.controller;

import org.dromara.common.core.domain.model.LoginBody;
import org.dromara.common.web.handler.GlobalExceptionHandler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class AuthControllerErrorResponseTest {

    @Tag("local")
    @Test
    void malformedLoginBodyReturnsSafeReasonAndErrorId() throws Exception {
        MockMvc mockMvc = standaloneSetup(new TestLoginController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

        String response = mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("\"invalid-encrypted-payload\""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.msg").value(matchesPattern(
                "请求参数格式错误 \\[错误编号: \\d{8}\\]")))
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertFalse(response.contains("invalid-encrypted-payload"));
        assertFalse(response.contains("MismatchedInputException"));
    }

    @RestController
    static class TestLoginController {

        @PostMapping("/auth/login")
        void login(@RequestBody String body) {
            JsonMapper.builder().build().readValue(body, LoginBody.class);
        }
    }
}
