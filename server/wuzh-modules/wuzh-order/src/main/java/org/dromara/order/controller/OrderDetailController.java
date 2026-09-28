package org.dromara.order.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.constant.SystemConstants;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.common.excel.utils.ExcelBuilder;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.redis.annotation.RepeatSubmit;
import org.dromara.order.domain.OrderDetailDtos;
import org.dromara.order.service.OrderDetailService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/order/details")
@SaCheckRole(SystemConstants.SUPER_ADMIN_ROLE_KEY)
public class OrderDetailController {

    private final OrderDetailService orderDetailService;

    @GetMapping("/list")
    @SaCheckPermission("order:detail:list")
    public R<PageResult<OrderDetailDtos.DetailView>> list(OrderDetailDtos.Query query, PageQuery pageQuery) {
        return R.ok(orderDetailService.queryPage(query, pageQuery));
    }

    @GetMapping("/{id}")
    @SaCheckPermission("order:detail:query")
    public R<OrderDetailDtos.DetailView> getInfo(
        @NotNull(message = "主键不能为空") @PathVariable Long id
    ) {
        return R.ok(orderDetailService.queryById(id));
    }

    @Log(title = "订单明细新增", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    @SaCheckPermission("order:detail:add")
    public R<Void> add(@Validated(AddGroup.class) @RequestBody OrderDetailDtos.SaveRequest request) {
        orderDetailService.create(request);
        return R.ok();
    }

    @Log(title = "订单明细修改", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping
    @SaCheckPermission("order:detail:edit")
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody OrderDetailDtos.SaveRequest request) {
        orderDetailService.update(request);
        return R.ok();
    }

    @Log(title = "订单明细分配用户", businessType = BusinessType.GRANT)
    @PutMapping("/{id}/owner/{userId}")
    @SaCheckRole("superadmin")
    @SaCheckPermission("order:detail:assign")
    public R<Void> assignOwner(@PathVariable Long id, @PathVariable Long userId) {
        orderDetailService.assignOwner(id, userId);
        return R.ok();
    }

    @Log(title = "订单明细删除", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    @SaCheckPermission("order:detail:remove")
    public R<Void> remove(
        @NotEmpty(message = "主键不能为空") @PathVariable Long[] ids
    ) {
        orderDetailService.delete(List.of(ids));
        return R.ok();
    }

    @Log(title = "订单明细导出", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    @SaCheckPermission("order:detail:export")
    public void export(OrderDetailDtos.Query query, HttpServletResponse response) {
        ExcelBuilder.of(orderDetailService.queryExport(query), OrderDetailDtos.ExportRow.class)
            .sheetName("订单明细")
            .toResponse(response);
    }
}
