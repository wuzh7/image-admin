package org.dromara.order.store.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.image.storage.ImageStorage;
import org.dromara.order.store.service.StoreListingService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.http.CacheControl;

import java.nio.file.Files;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/store/listing-exports")
public class StoreListingController {
    private final StoreListingService service;

    @GetMapping
    @SaCheckPermission(value = {"store:listing-export:list", "store:listing-export:add"}, mode = SaMode.OR)
    public R<List<StoreListingService.ExportView>> list(@RequestParam(required = false) Long batchId,
                                                         @RequestParam(required = false) Long shopId,
                                                         @RequestParam(required = false) Long categoryId) {
        return R.ok(service.list(batchId, shopId, categoryId));
    }

    @PostMapping
    @SaCheckPermission("store:listing-export:add")
    public R<StoreListingService.ExportView> create(@Valid @RequestBody StoreListingService.GenerateRequest request) {
        return R.ok(service.create(request));
    }

    @PostMapping("/from-images")
    @SaCheckPermission("store:listing-export:add")
    public R<List<StoreListingService.ExportView>> createFromImages(@Valid @RequestBody StoreListingService.ImageGenerateRequest request) {
        return R.ok(service.createFromImages(request));
    }

    @PostMapping("/{id}/retry")
    @SaCheckPermission("store:listing-export:retry")
    public R<StoreListingService.ExportView> retry(@PathVariable long id) {
        return R.ok(service.retry(id));
    }

    @GetMapping("/{id}/download")
    @SaCheckPermission("store:listing-export:download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable long id) {
        ImageStorage.LocalFile file = service.download(id);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=listing-" + id + ".xlsx")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(output -> { try (file) { Files.copy(file.path(), output); } });
    }

    @GetMapping("/public-results")
    public ResponseEntity<StreamingResponseBody> publicResult(@RequestParam String key) {
        ImageStorage.LocalFile file = service.publicResult(key);
        String contentType = key.endsWith(".png") ? "image/png" : key.endsWith(".webp") ? "image/webp" : "image/jpeg";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(contentType)).cacheControl(CacheControl.noCache())
            .body(output -> { try (file) { Files.copy(file.path(), output); } });
    }
}
