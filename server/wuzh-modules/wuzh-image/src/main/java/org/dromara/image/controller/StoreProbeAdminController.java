package org.dromara.image.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.redis.annotation.RepeatSubmit;
import org.dromara.image.domain.StoreProbeDtos;
import org.dromara.image.service.StoreProbeService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/system/store-probe")
public class StoreProbeAdminController {
    private final StoreProbeService service;

    @SaCheckPermission("system:store-probe:list")
    @GetMapping("/list")
    public R<PageResult<StoreProbeDtos.View>> list(@RequestParam(required = false) String storeId,
                                                    @RequestParam(required = false) String storeName,
                                                    PageQuery pageQuery) {
        return R.ok(service.list(storeId, storeName, pageQuery));
    }

    @SaCheckPermission("system:store-probe:query")
    @GetMapping("/{id}")
    public R<StoreProbeDtos.View> get(@PathVariable long id) {
        return R.ok(service.get(id));
    }

    @Log(title = "线上店铺维护", businessType = BusinessType.INSERT)
    @SaCheckPermission("system:store-probe:add")
    @RepeatSubmit
    @PostMapping
    public R<StoreProbeDtos.View> add(@Valid @RequestBody StoreProbeDtos.SaveRequest request) {
        return R.ok(service.save(request, false));
    }

    @Log(title = "线上店铺维护", businessType = BusinessType.UPDATE)
    @SaCheckPermission("system:store-probe:edit")
    @RepeatSubmit
    @PutMapping
    public R<StoreProbeDtos.View> edit(@Valid @RequestBody StoreProbeDtos.SaveRequest request) {
        if (request.id() == null) return R.fail("id不能为空");
        return R.ok(service.save(request, true));
    }

    @Log(title = "线上店铺维护", businessType = BusinessType.DELETE)
    @SaCheckPermission("system:store-probe:remove")
    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable long id) {
        service.remove(id);
        return R.ok();
    }
}
