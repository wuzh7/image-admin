package org.dromara.order.tro;

import cn.dev33.satoken.annotation.SaCheckRole;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.constant.SystemConstants;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.redis.annotation.RepeatSubmit;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@SaCheckRole(SystemConstants.SUPER_ADMIN_ROLE_KEY)
@RequestMapping("/tro/cases")
public class TroCaseController {
    private final TroCaseService service;

    @GetMapping("/list")
    public R<PageResult<TroCase>> list(String caseNoFormat, String caseName, String plaintiff, PageQuery pageQuery) {
        return R.ok(service.list(caseNoFormat, caseName, plaintiff, pageQuery));
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RepeatSubmit
    public R<Integer> importFile(@RequestPart("file") MultipartFile file) {
        return R.ok(service.importFile(file));
    }
}
