package org.dromara.order.store.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import org.dromara.common.core.constant.SystemConstants;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.image.storage.ImageStorage;
import org.dromara.order.store.domain.StoreDtos;
import org.dromara.order.store.domain.StoreRenderItem;
import org.dromara.order.store.service.StoreWorkflowService;
import org.dromara.order.store.service.StoreReferenceService;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/store")
public class StoreWorkflowController {
    private final StoreWorkflowService service;
    private final StoreReferenceService references;
    @Value("${store.render.worker-token:}")
    private String workerToken;
    private void checkWorkerToken(String token) { if (workerToken.isBlank() || token == null || !java.security.MessageDigest.isEqual(workerToken.getBytes(java.nio.charset.StandardCharsets.UTF_8), token.getBytes(java.nio.charset.StandardCharsets.UTF_8))) throw new org.dromara.common.core.exception.ServiceException("渲染 Worker 未授权"); }

    @GetMapping("/catalog") public R<List<StoreDtos.CategoryView>> catalog() { return R.ok(service.catalog()); }
    @GetMapping("/shops") public R<List<StoreDtos.ShopView>> shops() { return R.ok(service.shops()); }
    @GetMapping("/references/{kind}/options") public R<List<StoreReferenceService.Option>> referenceOptions(@PathVariable String kind) { return R.ok(references.options(kind, true)); }
    @SaCheckRole(SystemConstants.SUPER_ADMIN_ROLE_KEY)
    @GetMapping("/references/{kind}") public R<List<StoreReferenceService.Option>> references(@PathVariable String kind) { return R.ok(references.options(kind, false)); }
    @SaCheckRole(SystemConstants.SUPER_ADMIN_ROLE_KEY)
    @PostMapping("/references/{kind}") public R<StoreReferenceService.Option> addReference(@PathVariable String kind, @RequestBody StoreReferenceService.SaveOption request) { return R.ok(references.save(kind, null, request)); }
    @SaCheckRole(SystemConstants.SUPER_ADMIN_ROLE_KEY)
    @PutMapping("/references/{kind}/{id}") public R<StoreReferenceService.Option> editReference(@PathVariable String kind, @PathVariable long id, @RequestBody StoreReferenceService.SaveOption request) { return R.ok(references.save(kind, id, request)); }
    @SaCheckRole(SystemConstants.SUPER_ADMIN_ROLE_KEY)
    @DeleteMapping("/references/{kind}/{id}") public R<Void> deleteReference(@PathVariable String kind, @PathVariable long id) { references.delete(kind, id); return R.ok(); }
    @SaCheckPermission("store:shop:list")
    @GetMapping("/operators") public R<List<StoreReferenceService.Operator>> operators() { return R.ok(references.operators()); }
    @GetMapping("/calibrations") public R<List<StoreDtos.CalibrationView>> calibrations() { return R.ok(service.calibrations()); }
    @PostMapping("/calibrations") public R<StoreDtos.CalibrationView> addCalibration(@Valid @RequestBody StoreDtos.CalibrationRequest request) { return R.ok(service.saveCalibration(request, null)); }
    @PatchMapping("/calibrations/{id}") public R<StoreDtos.CalibrationView> editCalibration(@PathVariable long id, @Valid @RequestBody StoreDtos.CalibrationRequest request) { return R.ok(service.saveCalibration(request, id)); }
    @DeleteMapping("/calibrations/{id}") public R<Void> deleteCalibration(@PathVariable long id) { service.deleteCalibration(id); return R.ok(); }
    @PostMapping("/shops") public R<StoreDtos.ShopView> addShop(@Valid @RequestBody StoreDtos.ShopRequest request) { return R.ok(service.saveShop(request, null)); }
    @PatchMapping("/shops/{id}") public R<StoreDtos.ShopView> editShop(@PathVariable long id, @Valid @RequestBody StoreDtos.ShopRequest request) { return R.ok(service.saveShop(request, id)); }

    @GetMapping("/template-groups") public R<List<StoreDtos.TemplateGroupView>> groups(@RequestParam(required = false) Long categoryId, @RequestParam(defaultValue = "false") boolean includeDeleted) { return R.ok(service.groups(categoryId, includeDeleted)); }
    @GetMapping("/templates") public R<List<StoreDtos.TemplateView>> templates(@RequestParam(required=false) Long groupId, @RequestParam(required=false) Long categoryId) { return R.ok(service.templates(groupId, categoryId)); }
    @GetMapping("/template-groups/{id}") public R<StoreDtos.TemplateGroupView> group(@PathVariable long id) { return R.ok(service.group(id)); }
    @PostMapping("/template-groups") public R<StoreDtos.TemplateGroupView> addGroup(@Valid @RequestBody StoreDtos.TemplateGroupRequest request) { return R.ok(service.saveGroup(request, null)); }
    @PatchMapping("/template-groups/{id}") public R<StoreDtos.TemplateGroupView> editGroup(@PathVariable long id, @Valid @RequestBody StoreDtos.TemplateGroupRequest request) { return R.ok(service.saveGroup(request, id)); }
    @DeleteMapping("/template-groups/{id}") public R<Void> deleteGroup(@PathVariable long id) { service.deleteGroup(id); return R.ok(); }
    @PatchMapping("/template-groups/{id}/status") public R<Void> groupStatus(@PathVariable long id, @RequestBody StoreDtos.GroupStatusRequest request) { service.setGroupStatus(id, request.status()); return R.ok(); }
    @PostMapping("/template-groups/upload-policy") public R<ImageStorage.UploadPolicy> uploadPolicy() { return R.ok(service.uploadPolicy()); }
    @PostMapping(value = "/template-groups/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE) public R<StoreDtos.UploadView> upload(@RequestPart("file") MultipartFile file) { return R.ok(service.upload(file)); }

    @PutMapping("/listing-templates/{id}/profile") public R<StoreDtos.ListingTemplateView> saveListingProfile(@PathVariable long id, @Valid @RequestBody StoreDtos.ListingProfileRequest request) { return R.ok(service.saveListingProfile(id, request)); }
    @PostMapping(value = "/listing-templates/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE) public R<StoreDtos.UploadView> uploadListing(@RequestPart("file") MultipartFile file) { return R.ok(service.uploadListing(file)); }
    @GetMapping("/files") public ResponseEntity<StreamingResponseBody> ownedFile(@RequestParam String key) { return file(service.ownedFile(key)); }
    @GetMapping("/listing-templates") public R<List<StoreDtos.ListingTemplateView>> listingTemplates(@RequestParam(required=false) Long shopId, @RequestParam(required=false) Long categoryId) { return R.ok(service.listingTemplates(shopId, categoryId)); }
    @PostMapping("/listing-templates") public R<StoreDtos.ListingTemplateView> addListingTemplate(@Valid @RequestBody StoreDtos.ListingTemplateRequest request) { return R.ok(service.saveListingTemplate(request, null)); }
    @PatchMapping("/listing-templates/{id}") public R<StoreDtos.ListingTemplateView> editListingTemplate(@PathVariable long id, @Valid @RequestBody StoreDtos.ListingTemplateRequest request) { return R.ok(service.saveListingTemplate(request, id)); }
    @DeleteMapping("/listing-templates/{id}") public R<Void> deleteListingTemplate(@PathVariable long id) { service.deleteListingTemplate(id); return R.ok(); }

    @PostMapping("/render-batches") public R<StoreDtos.RenderBatchView> createBatch(@Valid @RequestBody StoreDtos.RenderBatchRequest request) { return R.ok(service.createBatch(request)); }
    @GetMapping("/render-batches") public R<StoreDtos.PageView<StoreDtos.RenderBatchView>> batches(@RequestParam(required = false) String status, @RequestParam(required = false) Long shopId, @RequestParam(required = false) Long categoryId, @RequestParam(defaultValue="1") int pageNum, @RequestParam(defaultValue="20") int pageSize) { return R.ok(service.batches(status, shopId, categoryId, pageNum, pageSize)); }
    @GetMapping("/render-batches/{id}") public R<StoreDtos.RenderBatchView> batch(@PathVariable long id) { return R.ok(service.batch(id)); }
    @PostMapping("/render-batches/{id}/retry") public R<StoreDtos.RenderBatchView> retryBatch(@PathVariable long id) { return R.ok(service.retryBatch(id)); }
    @GetMapping("/render-batches/{id}/download") public ResponseEntity<StreamingResponseBody> download(@PathVariable long id, @RequestParam(defaultValue="ORIGINAL_NAME") String nameMode) {
        List<StoreDtos.ArchiveFile> files = service.archiveFiles(id, nameMode);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=render-" + id + ".zip")
            .contentType(MediaType.parseMediaType("application/zip")).body(output -> {
                ZipOutputStream zip = new ZipOutputStream(output);
                for (StoreDtos.ArchiveFile entry : files) {
                    try (ImageStorage.LocalFile file = service.archiveFile(entry)) {
                        zip.putNextEntry(new ZipEntry(entry.filename()));
                        Files.copy(file.path(), zip);
                        zip.closeEntry();
                    }
                }
                zip.finish();
            });
    }
    @GetMapping("/render-results") public R<StoreDtos.PageView<StoreRenderItem>> results(@RequestParam(required = false) Long batchId, @RequestParam(defaultValue="1") int pageNum, @RequestParam(defaultValue="24") int pageSize) { return R.ok(service.results(batchId, pageNum, pageSize)); }
    @GetMapping("/render-result-groups") public R<StoreDtos.PageView<StoreDtos.RenderGroupView>> resultGroups(@RequestParam long batchId, @RequestParam(defaultValue="1") int pageNum, @RequestParam(defaultValue="6") int pageSize) { return R.ok(service.resultGroups(batchId, pageNum, pageSize)); }
    @GetMapping("/render-logs") public R<StoreDtos.PageView<StoreDtos.RenderLogView>> renderLogs(@RequestParam(required = false) String userName, @RequestParam(defaultValue="1") int pageNum, @RequestParam(defaultValue="20") int pageSize) { return R.ok(service.renderLogs(userName, pageNum, pageSize)); }

    @PostMapping(value = "/internal/render-items/{id}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE) public R<StoreDtos.UploadView> uploadResult(@PathVariable long id, @RequestParam String workerId, @RequestParam String leaseToken, @RequestPart("file") MultipartFile file, @RequestHeader("X-Render-Worker-Token") String token) { checkWorkerToken(token); return R.ok(service.uploadResult(id, workerId, leaseToken, file)); }
    @GetMapping("/internal/images/{id}/access") public R<String> imageAccess(@PathVariable long id, @RequestHeader("X-Render-Worker-Token") String token) { checkWorkerToken(token); return R.data(service.imageAccess(id)); }

    @GetMapping("/internal/templates/{id}/access") public R<String> templateAccess(@PathVariable long id, @RequestHeader("X-Render-Worker-Token") String token) { checkWorkerToken(token); return R.data(service.templateAccess(id)); }
    @GetMapping("/internal/masks/{id}/access") public R<String> maskAccess(@PathVariable long id, @RequestHeader("X-Render-Worker-Token") String token) { checkWorkerToken(token); return R.data(service.maskAccess(id)); }
    @GetMapping("/internal/images/{id}/file") public ResponseEntity<StreamingResponseBody> imageFile(@PathVariable long id, @RequestHeader("X-Render-Worker-Token") String token) { checkWorkerToken(token); return file(service.imageFile(id)); }
    @GetMapping("/internal/templates/{id}/file") public ResponseEntity<StreamingResponseBody> templateFile(@PathVariable long id, @RequestHeader("X-Render-Worker-Token") String token) { checkWorkerToken(token); return file(service.templateFile(id)); }
    @GetMapping("/internal/masks/{id}/file") public ResponseEntity<StreamingResponseBody> maskFile(@PathVariable long id, @RequestHeader("X-Render-Worker-Token") String token) { checkWorkerToken(token); return file(service.maskFile(id)); }
    private ResponseEntity<StreamingResponseBody> file(ImageStorage.LocalFile file) { return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(output -> { try (file) { Files.copy(file.path(), output); } }); }

    @PostMapping("/internal/render-items/claim") public R<StoreDtos.ClaimView> claim(@Valid @RequestBody StoreDtos.ClaimRequest request, @RequestHeader("X-Render-Worker-Token") String token) { checkWorkerToken(token); return R.ok(service.claim(request)); }
    @PostMapping("/internal/render-items/{id}/renew") public R<Void> renew(@PathVariable long id, @Valid @RequestBody StoreDtos.RenewRequest request, @RequestHeader("X-Render-Worker-Token") String token) { checkWorkerToken(token); service.renew(id, request); return R.ok(); }
    @PostMapping("/internal/render-items/{id}/complete") public R<Void> complete(@PathVariable long id, @Valid @RequestBody StoreDtos.CompleteRequest request, @RequestHeader("X-Render-Worker-Token") String token) { checkWorkerToken(token); service.complete(id, request); return R.ok(); }
    @PostMapping("/internal/render-items/{id}/fail") public R<Void> fail(@PathVariable long id, @Valid @RequestBody StoreDtos.FailRequest request, @RequestHeader("X-Render-Worker-Token") String token) { checkWorkerToken(token); service.fail(id, request); return R.ok(); }
}
