package org.dromara.image.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.image.domain.InfringementDtos;
import org.dromara.image.service.ImageDataLock;
import org.dromara.image.service.InfringementService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/image/infringement")
@RequiredArgsConstructor
public class InfringementController {
    private final InfringementService service;
    private final ImageDataLock imageDataLock;

    @SaCheckPermission("image:infringement:list")
    @GetMapping("/tasks")
    public R<PageResult<InfringementDtos.TaskSummary>> tasks(PageQuery pageQuery) {
        return R.ok(service.listTasks(pageQuery));
    }

    @SaCheckPermission("image:infringement:list")
    @GetMapping("/tasks/{id}/items")
    public R<PageResult<InfringementDtos.ItemView>> items(@PathVariable long id,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String riskLevel,
        PageQuery pageQuery) {
        return R.ok(service.listItems(id, status, riskLevel, pageQuery));
    }

    @SaCheckPermission("image:infringement:list")
    @SaCheckRole("superadmin")
    @GetMapping("/tasks/{id}/calls")
    public R<PageResult<InfringementDtos.CallLogView>> calls(@PathVariable long id,
        @RequestParam(required = false) Long imageId,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String executionMode,
        PageQuery pageQuery) {
        return R.ok(service.listCallLogs(id, imageId, status, executionMode, pageQuery));
    }

    @Log(title = "图片侵权失败重检", businessType = BusinessType.UPDATE)
    @SaCheckPermission("image:infringement:retry")
    @PostMapping("/tasks/{id}/retry-failed")
    public R<InfringementDtos.RetryResult> retryFailed(@PathVariable long id) {
        return R.ok(imageDataLock.read(() -> service.retryFailedTask(id)));
    }

    @SaCheckPermission("image:infringement:list")
    @SaCheckRole("superadmin")
    @GetMapping("/tasks/{id}/calls/{callId}/response")
    public R<InfringementDtos.CallResponseView> callResponse(@PathVariable long id, @PathVariable long callId) {
        return R.ok(service.callResponse(id, callId));
    }

    @Log(title = "图片侵权单张重检", businessType = BusinessType.UPDATE)
    @SaCheckPermission("image:infringement:retry")
    @PostMapping("/images/{id}/rescan")
    public R<InfringementDtos.RetryResult> rescanImage(@PathVariable long id) {
        return R.ok(imageDataLock.read(() -> service.rescanImage(id)));
    }
}
