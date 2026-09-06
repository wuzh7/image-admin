package org.dromara.system.domain.bo;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.dromara.system.api.model.RegisterBody;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("local")
class SysUserBoTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void requiresPhoneNumberAndEmail() {
        SysUserBo user = new SysUserBo();
        user.setUserName("tester");
        user.setNickName("测试用户");
        user.setRoleIds(new Long[]{1L});

        var messages = validator.validate(user).stream().map(error -> error.getMessage()).toList();

        assertTrue(messages.contains("手机号码不能为空"));
        assertTrue(messages.contains("邮箱不能为空"));
    }

    @Test
    void registrationRequiresPhoneNumberAndEmail() {
        RegisterBody request = new RegisterBody();
        request.setUsername("tester");
        request.setPassword("password");
        request.setUserType("sys_user");

        var fields = validator.validate(request).stream().map(error -> error.getPropertyPath().toString()).toList();

        assertTrue(fields.contains("phoneNumber"));
        assertTrue(fields.contains("email"));
    }
}
