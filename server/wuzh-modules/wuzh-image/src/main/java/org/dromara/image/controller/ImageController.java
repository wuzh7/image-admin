package org.dromara.image.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.constant.SystemConstants;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.redis.annotation.RepeatSubmit;
import org.dromara.image.domain.ImageDtos;
import org.dromara.image.service.ImageService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

@Validated
@RestController
@RequiredArgsConstructor
public class ImageController {

    private final ImageService imageService;
    private final org.dromara.image.service.ImageUploadService uploadService;

    @SaCheckPermission("image:dedup:upload")
    @GetMapping("/image/storage")
    public R<Boolean> storageMode() { return R.ok(uploadService.directUpload()); }

    @SaCheckPermission("image:dedup:upload")
    @PostMapping("/image/batches/{id}/upload-tickets")
    public R<List<org.dromara.image.service.ImageUploadService.Ticket>> uploadTickets(@PathVariable long id,
        @Valid @RequestBody org.dromara.image.service.ImageUploadService.PrepareRequest request) {
        return R.ok(uploadService.prepare(id, request));
    }

    @SaCheckPermission("image:dedup:upload")
    @PostMapping("/image/batches/{id}/upload-complete")
    public R<Void> uploadComplete(@PathVariable long id,
        @Valid @RequestBody org.dromara.image.service.ImageUploadService.CompleteRequest request) {
        uploadService.complete(id, request);
        return R.ok();
    }

    @SaCheckPermission("image:dedup:query")
    @GetMapping("/images/{id}/access")
    public ResponseEntity<R<String>> access(@PathVariable long id,
        @RequestParam(defaultValue = "false") boolean thumbnail) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(R.ok(imageService.accessUrl(id, thumbnail)));
    }

    @Log(title = "创建图片批次", businessType = BusinessType.INSERT)
    @SaCheckPermission("image:dedup:upload")
    @RepeatSubmit
    @PostMapping("/image/batches")
    public R<ImageDtos.BatchView> createBatch(@Valid @RequestBody ImageDtos.CreateBatchRequest request) {
        return R.ok(imageService.createBatch(request));
    }

    @Log(title = "图片批次上传", businessType = BusinessType.INSERT)
    @SaCheckPermission("image:dedup:upload")
    @PostMapping(value = "/image/batches/{id}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<Integer> upload(@PathVariable long id, @RequestPart("files") List<MultipartFile> files) {
        return R.ok(imageService.upload(id, files));
    }

    @Log(title = "启动图片检测", businessType = BusinessType.UPDATE)
    @SaCheckPermission("image:dedup:start")
    @RepeatSubmit
    @PostMapping("/image/batches/{id}/start")
    public R<Void> start(@PathVariable long id) {
        imageService.start(id);
        return R.ok();
    }

    @SaCheckPermission("image:dedup:query")
    @GetMapping("/image/batches")
    public R<PageResult<ImageDtos.BatchSummary>> batches(PageQuery pageQuery,
                                                         @RequestParam(required = false) String status) {
        return R.ok(imageService.listBatches(pageQuery, status));
    }

    @SaCheckPermission("image:dedup:query")
    @GetMapping("/image/batches/{id}")
    public R<ImageDtos.BatchView> batch(@PathVariable long id) {
        return R.ok(imageService.getBatch(id));
    }

    @Log(title = "图片人工确认", businessType = BusinessType.UPDATE)
    @SaCheckPermission("image:dedup:confirm")
    @PostMapping("/image/batches/{id}/confirm")
    public R<Void> confirm(@PathVariable long id,
                           @Valid @RequestBody ImageDtos.ConfirmBatchRequest request) {
        imageService.confirm(id, request);
        return R.ok();
    }

    @Log(title = "图片检测重试", businessType = BusinessType.UPDATE)
    @SaCheckPermission("image:dedup:retry")
    @PostMapping("/image/batches/{id}/retry")
    public R<Void> retry(@PathVariable long id) {
        imageService.retry(id);
        return R.ok();
    }

    @SaCheckPermission("image:dedup:list")
    @GetMapping("/images")
    public R<PageResult<ImageDtos.ImageView>> images(ImageDtos.ImageQuery query, PageQuery pageQuery) {
        return R.ok(imageService.listApproved(query, pageQuery));
    }

    @SaCheckPermission("image:dedup:list")
    @GetMapping("/images/aspect-ratios")
    public R<List<String>> aspectRatios() {
        return R.ok(imageService.listAspectRatios());
    }

    @SaCheckPermission("image:dedup:query")
    @GetMapping("/images/{id}")
    public R<ImageDtos.ImageView> image(@PathVariable long id) {
        return R.ok(imageService.getImage(id));
    }

    @SaCheckPermission("image:dedup:query")
    @GetMapping("/images/{id}/content")
    public ResponseEntity<InputStreamResource> content(
        @PathVariable long id,
        @RequestParam(defaultValue = "false") boolean thumbnail
    ) throws IOException {
        ImageService.ImageContent content = imageService.content(id, thumbnail);
        InputStreamResource body = new InputStreamResource(Files.newInputStream(content.path()));
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(content.contentType()))
            .contentLength(Files.size(content.path()))
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                .filename(content.filename(), StandardCharsets.UTF_8).build().toString())
            .body(body);
    }

    @Log(title = "正式图库删除", businessType = BusinessType.DELETE)
    @SaCheckRole(SystemConstants.SUPER_ADMIN_ROLE_KEY)
    @DeleteMapping("/images/{id}")
    public R<Void> delete(@PathVariable long id) {
        imageService.delete(id);
        return R.ok();
    }

    @SaCheckPermission("image:dedup:list")
    @GetMapping("/image/health")
    public R<ImageDtos.HealthView> health() {
        return R.ok(imageService.health());
    }

    @Log(title = "修复图片向量索引", businessType = BusinessType.UPDATE)
    @SaCheckRole(SystemConstants.SUPER_ADMIN_ROLE_KEY)
    @PostMapping("/image/vector-index/repair")
    public R<ImageDtos.VectorRepairView> repairVectorIndex() {
        return R.ok(imageService.repairVectorIndex());
    }

    @GetMapping("/image/categories/options")
    public R<List<ImageDtos.CategoryView>> categoryOptions() {
        return R.ok(imageService.categoryOptions());
    }

    @SaCheckPermission("image:category:list")
    @GetMapping("/image/categories")
    public R<List<ImageDtos.CategoryView>> categories() {
        return R.ok(imageService.listCategories());
    }

    @Log(title = "图片类目新增", businessType = BusinessType.INSERT)
    @SaCheckPermission("image:category:add")
    @PostMapping("/image/categories")
    public R<ImageDtos.CategoryView> createCategory(
        @Valid @RequestBody ImageDtos.SaveCategoryRequest request
    ) {
        return R.ok(imageService.createCategory(request));
    }

    @Log(title = "图片类目修改", businessType = BusinessType.UPDATE)
    @SaCheckPermission("image:category:edit")
    @PutMapping("/image/categories/{id}")
    public R<ImageDtos.CategoryView> updateCategory(
        @PathVariable long id,
        @Valid @RequestBody ImageDtos.SaveCategoryRequest request
    ) {
        return R.ok(imageService.updateCategory(id, request));
    }

    @Log(title = "图片类目删除", businessType = BusinessType.DELETE)
    @SaCheckPermission("image:category:remove")
    @DeleteMapping("/image/categories/{id}")
    public R<Void> deleteCategory(@PathVariable long id) {
        imageService.deleteCategory(id);
        return R.ok();
    }
}
