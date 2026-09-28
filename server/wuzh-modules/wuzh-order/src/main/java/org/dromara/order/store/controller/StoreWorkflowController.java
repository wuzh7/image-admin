package org.dromara.order.store.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
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

    @SaCheckPermission(value = {"store:shop:list", "store:calibration:list", "store:template-group:list", "store:listing-template:list", "store:render:list", "store:render:add", "store:listing-export:list"}, mode = SaMode.OR)
    @GetMapping("/catalog") public R<List<StoreDtos.CategoryView>> catalog() { return R.ok(service.catalog()); }
    @SaCheckPermission(value = {"store:shop:list", "store:calibration:list", "store:template-group:list", "store:listing-template:list", "store:render:list", "store:render:add", "store:listing-export:list"}, mode = SaMode.OR)
    @GetMapping("/shops") public R<List<StoreDtos.ShopView>> shops() { return R.ok(service.shops()); }
    @GetMapping("/references/{kind}/options") public R<List<StoreReferenceService.Option>> referenceOptions(@PathVariable String kind) { return R.ok(references.options(kind, true)); }
    @SaCheckRole(SystemConstants.SUPER_ADMIN_ROLE_KEY)
    @SaCheckPermission("store:reference:list")
    @GetMapping("/references/{kind}") public R<List<StoreReferenceService.Option>> references(@PathVariable String kind) { return R.ok(references.options(kind, false)); }
    @SaCheckRole(SystemConstants.SUPER_ADMIN_ROLE_KEY)
    @SaCheckPermission("store:reference:add")
    @PostMapping("/references/{kind}") public R<StoreReferenceService.Option> addReference(@PathVariable String kind, @RequestBody StoreReferenceService.SaveOption request) { return R.ok(references.save(kind, null, request)); }
    @SaCheckRole(SystemConstants.SUPER_ADMIN_ROLE_KEY)
    @SaCheckPermission("store:reference:edit")
    @PutMapping("/references/{kind}/{id}") public R<StoreReferenceService.Option> editReference(@PathVariable String kind, @PathVariable long id, @RequestBody StoreReferenceService.SaveOption request) { return R.ok(references.save(kind, id, request)); }
    @SaCheckRole(SystemConstants.SUPER_ADMIN_ROLE_KEY)
    @SaCheckPermission("store:reference:remove")
    @DeleteMapping("/references/{kind}/{id}") public R<Void> deleteReference(@PathVariable String kind, @PathVariable long id) { references.delete(kind, id); return R.ok(); }
    @SaCheckPermission("store:shop:list")
    @GetMapping("/operators") public R<List<StoreReferenceService.Operator>> operators() { return R.ok(references.operators()); }
    @SaCheckPermission(value = {"store:calibration:list", "store:template-group:list"}, mode = SaMode.OR)
    @GetMapping("/calibrations") public R<List<StoreDtos.CalibrationView>> calibrations() { return R.ok(service.calibrations()); }
    @SaCheckPermission("store:calibration:add")
    @PostMapping("/calibrations") public R<StoreDtos.CalibrationView> addCalibration(@Valid @RequestBody StoreDtos.CalibrationRequest request) { return R.ok(service.saveCalibration(request, null)); }
    @SaCheckPermission("store:calibration:edit")
    @PatchMapping("/calibrations/{id}") public R<StoreDtos.CalibrationView> editCalibration(@PathVariable long id, @Valid @RequestBody StoreDtos.CalibrationRequest request) { return R.ok(service.saveCalibration(request, id)); }
    @SaCheckPermission("store:calibration:remove")
    @DeleteMapping("/calibrations/{id}") public R<Void> deleteCalibration(@PathVariable long id) { service.deleteCalibration(id); return R.ok(); }
    @SaCheckPermission("store:shop:add")
    @PostMapping("/shops") public R<StoreDtos.ShopView> addShop(@Valid @RequestBody StoreDtos.ShopRequest request) { return R.ok(service.saveShop(request, null)); }
    @SaCheckPermission("store:shop:edit")
    @PatchMapping("/shops/{id}") public R<StoreDtos.ShopView> editShop(@PathVariable long id, @Valid @RequestBody StoreDtos.ShopRequest request) { return R.ok(service.saveShop(request, id)); }

    @SaCheckPermission(value = {"store:template-group:list", "store:render:add"}, mode = SaMode.OR)
    @GetMapping("/template-groups") public R<List<StoreDtos.TemplateGroupView>> groups(@RequestParam(required = false) Long categoryId, @RequestParam(defaultValue = "false") boolean includeDeleted) { return R.ok(service.groups(categoryId, includeDeleted)); }
    @SaCheckPermission(value = {"store:template-group:list", "store:render:add"}, mode = SaMode.OR)
    @GetMapping("/templates") public R<List<StoreDtos.TemplateView>> templates(@RequestParam(required=false) Long groupId, @RequestParam(required=false) Long categoryId) { return R.ok(service.templates(groupId, categoryId)); }
    @SaCheckPermission("store:template-group:query")
    @GetMapping("/template-groups/{id}") public R<StoreDtos.TemplateGroupView> group(@PathVariable long id) { return R.ok(service.group(id)); }
    @SaCheckPermission("store:template-group:add")
    @PostMapping("/template-groups") public R<StoreDtos.TemplateGroupView> addGroup(@Valid @RequestBody StoreDtos.TemplateGroupRequest request) { return R.ok(service.saveGroup(request, null)); }
    @SaCheckPermission("store:template-group:edit")
    @PatchMapping("/template-groups/{id}") public R<StoreDtos.TemplateGroupView> editGroup(@PathVariable long id, @Valid @RequestBody StoreDtos.TemplateGroupRequest request) { return R.ok(service.saveGroup(request, id)); }
    @SaCheckPermission("store:template-group:remove")
    @DeleteMapping("/template-groups/{id}") public R<Void> deleteGroup(@PathVariable long id) { service.deleteGroup(id); return R.ok(); }
    @SaCheckPermission("store:template-group:edit")
    @PatchMapping("/template-groups/{id}/status") public R<Void> groupStatus(@PathVariable long id, @RequestBody StoreDtos.GroupStatusRequest request) { service.setGroupStatus(id, request.status()); return R.ok(); }
    @SaCheckPermission("store:template-group:upload")
    @PostMapping("/template-groups/upload-policy") public R<ImageStorage.UploadPolicy> uploadPolicy() { return R.ok(service.uploadPolicy()); }
    @SaCheckPermission("store:template-group:upload")
    @PostMapping(value = "/template-groups/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE) public R<StoreDtos.UploadView> upload(@RequestPart("file") MultipartFile file) { return R.ok(service.upload(file)); }

    @SaCheckPermission("store:listing-template:edit")
    @PutMapping("/listing-templates/{id}/profile") public R<StoreDtos.ListingTemplateView> saveListingProfile(@PathVariable long id, @Valid @RequestBody StoreDtos.ListingProfileRequest request) { return R.ok(service.saveListingProfile(id, request)); }
    @SaCheckPermission("store:listing-template:upload")
    @PostMapping(value = "/listing-templates/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE) public R<StoreDtos.UploadView> uploadListing(@RequestPart("file") MultipartFile file) { return R.ok(service.uploadListing(file)); }
    @GetMapping("/files") public ResponseEntity<StreamingResponseBody> ownedFile(@RequestParam String key) { return file(service.ownedFile(key)); }
    @SaCheckPermission(value = {"store:listing-template:list", "store:render:add"}, mode = SaMode.OR)
    @GetMapping("/listing-templates") public R<List<StoreDtos.ListingTemplateView>> listingTemplates(@RequestParam(required=false) Long shopId, @RequestParam(required=false) Long categoryId) { return R.ok(service.listingTemplates(shopId, categoryId)); }
    @SaCheckPermission("store:listing-template:add")
    @PostMapping("/listing-templates") public R<StoreDtos.ListingTemplateView> addListingTemplate(@Valid @RequestBody StoreDtos.ListingTemplateRequest request) { return R.ok(service.saveListingTemplate(request, null)); }
    @SaCheckPermission("store:listing-template:edit")
    @PatchMapping("/listing-templates/{id}") public R<StoreDtos.ListingTemplateView> editListingTemplate(@PathVariable long id, @Valid @RequestBody StoreDtos.ListingTemplateRequest request) { return R.ok(service.saveListingTemplate(request, id)); }
    @SaCheckPermission("store:listing-template:remove")
    @DeleteMapping("/listing-templates/{id}") public R<Void> deleteListingTemplate(@PathVariable long id) { service.deleteListingTemplate(id); return R.ok(); }

    @SaCheckPermission("store:render:add")
    @PostMapping("/render-batches") public R<StoreDtos.RenderBatchView> createBatch(@Valid @RequestBody StoreDtos.RenderBatchRequest request) { return R.ok(service.createBatch(request)); }
    @SaCheckPermission(value = {"store:render:list", "store:render:add", "store:listing-export:list"}, mode = SaMode.OR)
    @GetMapping("/render-batches") public R<StoreDtos.PageView<StoreDtos.RenderBatchView>> batches(@RequestParam(required = false) String status, @RequestParam(required = false) Long shopId, @RequestParam(required = false) Long categoryId, @RequestParam(defaultValue="1") int pageNum, @RequestParam(defaultValue="20") int pageSize) { return R.ok(service.batches(status, shopId, categoryId, pageNum, pageSize)); }
    @SaCheckPermission(value = {"store:render:query", "store:render:add"}, mode = SaMode.OR)
    @GetMapping("/render-batches/{id}") public R<StoreDtos.RenderBatchView> batch(@PathVariable long id) { return R.ok(service.batch(id)); }
    @SaCheckPermission("store:render:add")
    @PostMapping("/render-batches/{id}/stop") public R<StoreDtos.RenderBatchView> stopBatch(@PathVariable long id) { return R.ok(service.stopBatch(id)); }
    @SaCheckPermission("store:render:retry")
    @PostMapping("/render-batches/{id}/retry") public R<StoreDtos.RenderBatchView> retryBatch(@PathVariable long id) { return R.ok(service.retryBatch(id)); }
    @SaCheckPermission("store:render:download")
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
    @SaCheckPermission("store:render:download")
    @PostMapping("/render-batches/{id}/download-confirmed") public R<Void> confirmDownload(@PathVariable long id) { service.confirmDownload(id); return R.ok(); }
    @SaCheckPermission("store:render:add")
    @DeleteMapping("/render-batches/{id}/results") public R<Void> deleteResults(@PathVariable long id) { service.deleteResults(id); return R.ok(); }
    @SaCheckPermission("store:render:list")
    @GetMapping("/render-results") public R<StoreDtos.PageView<StoreRenderItem>> results(@RequestParam(required = false) Long batchId, @RequestParam(defaultValue="1") int pageNum, @RequestParam(defaultValue="24") int pageSize) { return R.ok(service.results(batchId, pageNum, pageSize)); }
    @SaCheckPermission("store:render:list")
    @GetMapping("/render-result-groups") public R<StoreDtos.PageView<StoreDtos.RenderGroupView>> resultGroups(@RequestParam long batchId, @RequestParam(defaultValue="1") int pageNum, @RequestParam(defaultValue="6") int pageSize) { return R.ok(service.resultGroups(batchId, pageNum, pageSize)); }
    @SaCheckPermission("store:render:log")
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
