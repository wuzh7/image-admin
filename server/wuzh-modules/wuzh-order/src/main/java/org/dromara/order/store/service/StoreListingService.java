package org.dromara.order.store.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.utils.IdGeneratorUtil;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.image.domain.ImageAsset;
import org.dromara.image.mapper.ImageAssetMapper;
import org.dromara.image.storage.ImageStorage;
import org.dromara.image.domain.InfringementScanItem;
import org.dromara.image.service.InfringementService;
import org.dromara.order.store.domain.*;
import org.dromara.order.store.domain.StoreDtos.RenderDestinationRequest;
import org.dromara.order.store.mapper.*;
import org.dromara.system.api.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
@RequiredArgsConstructor
public class StoreListingService {
    private final StoreListingExportMapper exportMapper;
    private final StoreListingTemplateMapper templateMapper;
    private final StoreRenderBatchMapper batchMapper;
    private final StoreRenderItemMapper itemMapper;
    private final StoreShopMapper shopMapper;
    private final ImageAssetMapper imageMapper;
    private final StoreObjectStorage storage;
    private final InfringementService infringementService;
    private final UserService users;

    @Value("${store.listing.public-base-url:}")
    private String publicBaseUrl;

    public record ExportView(Long id, Long batchId, Long shopId, Long listingTemplateId, String status,
                             String errorMessage, String filename) {}
    public record GenerateRequest(Long batchId, Long shopId, Long listingTemplateId) {}
    public record ImageGenerateRequest(@NotNull Long categoryId,
                                       @NotEmpty @Size(max = 10000) List<@NotNull Long> imageIds,
                                       @NotEmpty @Size(max = 20) List<@NotNull @Valid RenderDestinationRequest> destinations,
                                       @NotBlank @Size(max = 128) String idempotencyKey) {}

    public List<ExportView> list(Long batchId, Long shopId, Long categoryId) {
        if (batchId != null) requireBatch(batchId);
        var query = Wrappers.<StoreListingExport>lambdaQuery().eq(StoreListingExport::getOwnerId, owner());
        if (batchId != null) query.eq(StoreListingExport::getBatchId, batchId);
        if (shopId != null) query.eq(StoreListingExport::getShopId, shopId);
        if (categoryId != null) query.and(condition -> condition.inSql(StoreListingExport::getBatchId,
            "SELECT id FROM store_render_batch WHERE category_id=" + categoryId).or(original -> original
                .isNull(StoreListingExport::getBatchId).inSql(StoreListingExport::getListingTemplateId,
                    "SELECT id FROM store_listing_template WHERE category_id=" + categoryId)));
        return exportMapper.selectList(query.orderByDesc(StoreListingExport::getId))
            .stream().map(this::view).toList();
    }

    @Transactional
    public ExportView create(GenerateRequest request) {
        if (request == null || request.batchId() == null || request.shopId() == null || request.listingTemplateId() == null)
            throw new ServiceException("请选择已完成批次、店铺和上架表格");
        StoreRenderBatch batch = requireBatch(request.batchId());
        if (!"COMPLETED".equals(batch.getStatus())) throw new ServiceException("渲染尚未全部完成");
        StoreShop shop = shopMapper.selectById(request.shopId());
        StoreListingTemplate template = templateMapper.selectById(request.listingTemplateId());
        if (shop == null || !Objects.equals(shop.getOwnerId(), owner()) || !"ACTIVE".equals(shop.getStatus()) ||
            template == null || !Objects.equals(template.getOwnerId(), owner()) || !"ACTIVE".equals(template.getStatus()) ||
            !Objects.equals(template.getShopId(), shop.getId()) || !Objects.equals(template.getCategoryId(), batch.getCategoryId()))
            throw new ServiceException("店铺、类目与上架表格不匹配");
        StoreListingExport existing = exportMapper.selectOne(Wrappers.<StoreListingExport>lambdaQuery()
            .eq(StoreListingExport::getBatchId, batch.getId()).eq(StoreListingExport::getShopId, shop.getId()).last("limit 1"));
        if (existing != null) {
            if (!Objects.equals(existing.getListingTemplateId(), template.getId()))
                throw new ServiceException("该批次已绑定另一张上架表格，请新建渲染批次");
            if ("FAILED".equals(existing.getStatus())) {
                List<Long> imageIds = itemMapper.selectList(Wrappers.<StoreRenderItem>lambdaQuery().eq(StoreRenderItem::getBatchId, batch.getId()))
                    .stream().map(StoreRenderItem::getImageId).distinct().toList();
                existing.setListingKeywordsJson(JsonUtils.toJsonString(snapshotKeywords(imageIds, owner())));
                existing.setStatus("PENDING"); existing.setErrorMessage(null); exportMapper.updateById(existing);
            }
            return view(existing);
        }
        if (batch.getListingTemplateId() != null &&
            (!Objects.equals(batch.getShopId(), shop.getId()) || !Objects.equals(batch.getListingTemplateId(), template.getId())))
            throw new ServiceException("该批次未绑定此店铺表格");
        String profile = batch.getListingProfileJson() != null ? batch.getListingProfileJson() : template.getConfigJson();
        ProfileSettings.parse(profile);
        List<Long> imageIds = itemMapper.selectList(Wrappers.<StoreRenderItem>lambdaQuery().eq(StoreRenderItem::getBatchId, batch.getId()))
            .stream().map(StoreRenderItem::getImageId).distinct().toList();
        Map<String, String> keywords = snapshotKeywords(imageIds, owner());
        StoreListingExport result = new StoreListingExport();
        result.setId(Long.parseLong(IdGeneratorUtil.nextId())); result.setOwnerId(owner()); result.setBatchId(batch.getId());
        result.setShopId(shop.getId()); result.setListingTemplateId(template.getId()); result.setStatus("PENDING");
        result.setListingKeywordsJson(JsonUtils.toJsonString(keywords)); result.setListingProfileJson(profile);
        try { exportMapper.insert(result); } catch (DuplicateKeyException duplicate) {
            return view(exportMapper.selectOne(Wrappers.<StoreListingExport>lambdaQuery().eq(StoreListingExport::getBatchId, batch.getId()).eq(StoreListingExport::getShopId, shop.getId())));
        }
        return view(result);
    }

    @Transactional
    public List<ExportView> createFromImages(ImageGenerateRequest request) {
        long ownerId = owner();
        if (request.destinations().stream().map(StoreDtos.RenderDestinationRequest::shopId).distinct().count() != request.destinations().size())
            throw new ServiceException("目标店铺重复");
        List<Long> imageIds = request.imageIds().stream().distinct().toList();
        Map<String, String> keywords = snapshotKeywords(imageIds, ownerId);
        long matching = 0;
        for (int offset = 0; offset < imageIds.size(); offset += 500)
            matching += imageMapper.selectCount(Wrappers.<ImageAsset>lambdaQuery()
                .in(ImageAsset::getId, imageIds.subList(offset, Math.min(offset + 500, imageIds.size())))
                .eq(ImageAsset::getCreateBy, ownerId).eq(ImageAsset::getStatus, "APPROVED").eq(ImageAsset::getCategoryId, request.categoryId()));
        if (matching != imageIds.size()) throw new ServiceException("图片类目不一致或无权限");
        List<ExportView> result = new ArrayList<>();
        for (StoreDtos.RenderDestinationRequest destination : request.destinations()) {
            StoreShop shop = shopMapper.selectById(destination.shopId());
            StoreListingTemplate template = templateMapper.selectById(destination.listingTemplateId());
            if (shop == null || !Objects.equals(shop.getOwnerId(), ownerId) || !"ACTIVE".equals(shop.getStatus()) ||
                template == null || !Objects.equals(template.getOwnerId(), ownerId) || !"ACTIVE".equals(template.getStatus()) ||
                !Objects.equals(template.getShopId(), shop.getId()) || !Objects.equals(template.getCategoryId(), request.categoryId()))
                throw new ServiceException("店铺、类目与上架表格不匹配");
            String profile = StoreWorkflowService.listingProfile(template.getConfigJson(), destination.titleSelection());
            ProfileSettings.parse(profile);
            StoreListingExport existing = exportMapper.selectOne(Wrappers.<StoreListingExport>lambdaQuery()
                .eq(StoreListingExport::getOwnerId, ownerId).eq(StoreListingExport::getIdempotencyKey, request.idempotencyKey())
                .eq(StoreListingExport::getShopId, shop.getId()));
            if (existing != null) { result.add(view(existing)); continue; }
            StoreListingExport export = new StoreListingExport();
            export.setId(Long.parseLong(IdGeneratorUtil.nextId())); export.setOwnerId(ownerId);
            export.setShopId(shop.getId()); export.setListingTemplateId(template.getId());
            export.setListingProfileJson(profile); export.setListingKeywordsJson(JsonUtils.toJsonString(keywords));
            export.setImageIdsJson(JsonUtils.toJsonString(imageIds.stream().map(String::valueOf).toList()));
            export.setIdempotencyKey(request.idempotencyKey()); export.setStatus("PENDING");
            try { exportMapper.insert(export); } catch (DuplicateKeyException duplicate) {
                export = exportMapper.selectOne(Wrappers.<StoreListingExport>lambdaQuery()
                    .eq(StoreListingExport::getOwnerId, ownerId).eq(StoreListingExport::getIdempotencyKey, request.idempotencyKey())
                    .eq(StoreListingExport::getShopId, shop.getId()));
                if (export == null) throw duplicate;
            }
            result.add(view(export));
        }
        return result;
    }

    public ExportView retry(long id) {
        StoreListingExport export = requireExport(id);
        if (!Set.of("FAILED", "COMPLETED").contains(export.getStatus())) throw new ServiceException("当前上架表格不能重新生成");
        List<Long> imageIds = export.getBatchId() == null ? JsonUtils.parseArray(export.getImageIdsJson(), Long.class) :
            itemMapper.selectList(Wrappers.<StoreRenderItem>lambdaQuery().eq(StoreRenderItem::getBatchId, export.getBatchId()))
                .stream().map(StoreRenderItem::getImageId).distinct().toList();
        export.setListingKeywordsJson(JsonUtils.toJsonString(snapshotKeywords(imageIds, owner())));
        export.setStatus("PENDING"); export.setErrorMessage(null); exportMapper.updateById(export);
        return view(export);
    }

    Map<String, String> snapshotKeywords(List<Long> imageIds, long ownerId) {
        if (imageIds.isEmpty()) throw new ServiceException("任务没有图片");
        var policy = users.selectById(ownerId);
        if (policy == null) throw new ServiceException("用户不存在或已停用");
        Map<String, String> result = new HashMap<>();
        for (int start = 0; start < imageIds.size(); start += 500) {
            List<ImageAsset> images = imageMapper.selectByIds(imageIds.subList(start, Math.min(start + 500, imageIds.size())));
            List<Long> scanIds = images.stream().map(ImageAsset::getInfringementScanItemId).filter(Objects::nonNull).distinct().toList();
            Map<Long, InfringementScanItem> scans = scanIds.isEmpty() ? Map.of() : infringementService.currentResults(scanIds);
            for (ImageAsset image : images) {
                if (!Objects.equals(image.getCreateBy(), ownerId) || !"APPROVED".equals(image.getStatus()))
                    throw new ServiceException("原图不存在或无权限: " + image.getId());
                InfringementScanItem scan = image.getInfringementScanItemId() == null ? null : scans.get(image.getInfringementScanItemId());
                if (Boolean.FALSE.equals(policy.getListingAllRisksEnabled())
                    && !("LOW".equals(image.getRiskLevel()) && scan != null && "SUCCESS".equals(scan.getStatus())))
                    throw new ServiceException("仅低风险且分析完成的图片可以生成上架表格");
                String keyword = listingKeyword(image, scan);
                if (keyword == null) throw new ServiceException("原图 " + image.getId() + " 缺少可用的原文件名");
                result.put(String.valueOf(image.getId()), keyword);
            }
        }
        if (result.size() != imageIds.size()) throw new ServiceException("原图读取不完整");
        return result;
    }

    static String listingKeyword(ImageAsset image, InfringementScanItem scan) {
        String keywords = InfringementService.aiListingKeywords(scan);
        if (keywords != null) return keywords;
        String name = Objects.toString(image.getOriginalName(), "").replaceFirst("\\.[^.]+$", "").trim();
        return name.isBlank() || name.equals(".") || name.equals("..") ? null : name;
    }

    public ImageStorage.LocalFile download(long id) {
        StoreListingExport export = requireExport(id);
        if (!"COMPLETED".equals(export.getStatus()) || export.getOssKey() == null) throw new ServiceException("上架表格尚未生成");
        return storage.open(export.getOssKey());
    }

    public ImageStorage.LocalFile publicResult(String key) {
        if (key != null && key.matches("library/[0-9]{4}/[0-9]{2}/[0-9a-f/_-]+\\.(jpg|jpeg|png|webp)")) {
            Long count = imageMapper.selectCount(Wrappers.<ImageAsset>lambdaQuery().eq(ImageAsset::getStorageKey, key)
                .eq(ImageAsset::getStatus, "APPROVED")
                .apply("EXISTS (SELECT 1 FROM store_listing_export e WHERE e.owner_id=img_asset.create_by AND e.batch_id IS NULL " +
                    "AND e.status='COMPLETED' AND JSON_CONTAINS(e.image_ids_json, JSON_QUOTE(CAST(img_asset.id AS CHAR))))"));
            if (count == 0) throw new ServiceException("表格原图不存在");
            return storage.open(key);
        }
        if (key == null || !key.matches("store/results/[0-9]+/[0-9a-f-]+\\.(jpg|jpeg|png|webp)"))
            throw new ServiceException("成品图片地址无效");
        Long count = itemMapper.selectCount(Wrappers.<StoreRenderItem>query()
            .eq("result_oss_key", key).in("status", "SUCCEEDED", "SKIPPED"));
        if (count == 0) throw new ServiceException("成品图片不存在");
        return storage.open(key);
    }

    static String publicResultUrl(String base, String key) {
        String value = Objects.toString(base, "").trim();
        URI uri;
        try { uri = URI.create(value); }
        catch (IllegalArgumentException error) { throw new ServiceException("请配置完整的成品图片公开地址"); }
        boolean localHttp = "http".equals(uri.getScheme()) && Set.of("127.0.0.1", "localhost").contains(uri.getHost());
        if (!("https".equals(uri.getScheme()) || localHttp) || uri.getHost() == null ||
            !value.endsWith("/prod-api/store/listing-exports/public-results?key="))
            throw new ServiceException("请配置完整的成品图片公开地址");
        return value + URLEncoder.encode(key, StandardCharsets.UTF_8);
    }

    @Scheduled(fixedDelay = 5000)
    public void processPending() {
        exportMapper.activateReady();
        exportMapper.update(null, Wrappers.<StoreListingExport>lambdaUpdate().eq(StoreListingExport::getStatus, "GENERATING")
            .lt(StoreListingExport::getUpdateTime, java.time.LocalDateTime.now().minusHours(1)).set(StoreListingExport::getStatus, "PENDING"));
        List<StoreListingExport> pending = exportMapper.selectList(Wrappers.<StoreListingExport>lambdaQuery()
            .eq(StoreListingExport::getStatus, "PENDING").orderByAsc(StoreListingExport::getId).last("limit 3"));
        for (StoreListingExport export : pending) {
            int claimed = exportMapper.update(null, Wrappers.<StoreListingExport>lambdaUpdate()
                .eq(StoreListingExport::getId, export.getId()).eq(StoreListingExport::getStatus, "PENDING")
                .set(StoreListingExport::getStatus, "GENERATING").set(StoreListingExport::getUpdateTime, java.time.LocalDateTime.now()));
            if (claimed != 1) continue;
            try {
                export.setOssKey(generate(export)); export.setStatus("COMPLETED"); export.setErrorMessage(null);
            } catch (Exception failure) {
                export.setStatus("FAILED"); export.setErrorMessage(Objects.toString(failure.getMessage(), "上架表格生成失败"));
            }
            exportMapper.updateById(export);
        }
    }

    String generate(StoreListingExport export) throws Exception {
        StoreRenderBatch batch = export.getBatchId() == null ? null : batchMapper.selectById(export.getBatchId());
        if (export.getBatchId() != null && (batch == null || !"COMPLETED".equals(batch.getStatus())))
            throw new ServiceException("渲染尚未全部完成");
        StoreListingTemplate template = templateMapper.selectById(export.getListingTemplateId());
        if (template == null || !Objects.equals(template.getOwnerId(), export.getOwnerId()) ||
            !Objects.equals(template.getShopId(), export.getShopId()) || (batch != null && !Objects.equals(template.getCategoryId(), batch.getCategoryId())) ||
            (batch != null && export.getListingProfileJson() == null && !Objects.equals(batch.getListingTemplateId(), template.getId())))
            throw new ServiceException("上架表格已删除或与批次不匹配");
        String configuredBase = Objects.toString(publicBaseUrl, "").trim();
        String profileJson = export.getListingProfileJson() != null ? export.getListingProfileJson() :
            batch != null && batch.getListingProfileJson() != null ? batch.getListingProfileJson() : template.getConfigJson();
        ProfileSettings settings = ProfileSettings.parse(profileJson);
        Map<?, ?> keywordSnapshot = export.getListingKeywordsJson() == null ? Map.of() : JsonUtils.parseObject(export.getListingKeywordsJson(), Map.class);
        Path output = Files.createTempFile("store-listing-", ".xlsx");
        try (ImageStorage.LocalFile original = storage.open(template.getOssKey());
             StoreListingWorkbook workbook = StoreListingWorkbook.open(Files.newInputStream(original.path()))) {
            List<StoreRenderItem> items = batch == null ? List.of() : itemMapper.selectList(Wrappers.<StoreRenderItem>lambdaQuery()
                .eq(StoreRenderItem::getBatchId, batch.getId()).orderByAsc(StoreRenderItem::getId));
            if (batch != null && (items.size() != batch.getSelectedCount() || items.stream().anyMatch(item ->
                !Set.of("SUCCEEDED", "SKIPPED").contains(item.getStatus()) || item.getResultOssKey() == null)))
                throw new ServiceException("渲染结果不完整，不能生成上架表格");
            LinkedHashMap<Long, List<StoreRenderItem>> grouped = new LinkedHashMap<>();
            for (StoreRenderItem item : items) grouped.computeIfAbsent(item.getImageId(), ignored -> new ArrayList<>()).add(item);
            if (batch == null) for (Long imageId : JsonUtils.parseArray(export.getImageIdsJson(), Long.class)) grouped.put(imageId, List.of());
            if (grouped.isEmpty()) throw new ServiceException("任务没有图片");
            if ((long) grouped.size() * (workbook.skuCount() + 1) > workbook.capacity())
                throw new ServiceException("上架表预留行不足，请扩展模板后重新上传");
            List<Long> imageIds = new ArrayList<>(grouped.keySet());
            Map<Long, ImageAsset> images = new HashMap<>();
            for (int offset = 0; offset < imageIds.size(); offset += 500)
                imageMapper.selectByIds(imageIds.subList(offset, Math.min(offset + 500, imageIds.size())))
                    .forEach(image -> images.put(image.getId(), image));
            List<StoreListingWorkbook.Product> products = new ArrayList<>();
            int templateCount = items.size() / grouped.size();
            for (var group : grouped.entrySet()) {
                ImageAsset image = images.get(group.getKey());
                if (image == null || !Objects.equals(image.getCreateBy(), export.getOwnerId()) ||
                    (batch == null && (!"APPROVED".equals(image.getStatus()) || !Objects.equals(image.getCategoryId(), template.getCategoryId())))) throw new ServiceException("原图不存在或无权限: " + group.getKey());
                String keyword = Objects.toString(keywordSnapshot.get(String.valueOf(image.getId())), "").trim();
                if (keyword.isBlank()) keyword = listingKeyword(image, null);
                if (keyword == null) throw new ServiceException("原图 " + group.getKey() + " 缺少可用的原文件名");
                if (group.getValue().size() != templateCount) throw new ServiceException("原图 " + group.getKey() + " 的套图数量不完整");
                List<String> urls = batch == null ? List.of(storage.renderInputUrl(image.getStorageKey())
                    .orElseGet(() -> publicResultUrl(configuredBase, image.getStorageKey()))) : group.getValue().stream().map(item -> {
                    String key = item.getResultOssKey();
                    if (!key.matches("(store|render-public)/results/" + export.getOwnerId() + "/[0-9a-f-]+\\.(jpg|jpeg|png|webp)"))
                        throw new ServiceException("成品图片地址无效");
                    return key.startsWith("render-public/") ? storage.url(key, "image/*") : publicResultUrl(configuredBase, key);
                }).toList();
                if ((batch != null && settings.previewIndex > urls.size()) || urls.size() > workbook.carouselCount())
                    throw new ServiceException("预览图序号或轮播图列与套图数量不匹配");
                products.add(new StoreListingWorkbook.Product(String.valueOf(group.getKey()), settings.title(keyword), urls));
            }
            StoreListingWorkbook.Profile profile = settings.workbookProfile();
            if (batch == null) profile = new StoreListingWorkbook.Profile(1, profile.skuStart(), profile.skuDigits(),
                profile.skuSeparator(), profile.detailSeparator(), profile.carouselScope());
            workbook.fill(products, profile);
            try (var outputStream = Files.newOutputStream(output)) { workbook.write(outputStream); }
            return storage.saveGenerated(export.getOwnerId(), output);
        } finally { Files.deleteIfExists(output); }
    }

    private StoreRenderBatch requireBatch(long id) {
        StoreRenderBatch batch = batchMapper.selectById(id);
        if (batch == null || !Objects.equals(batch.getOwnerId(), owner())) throw new ServiceException("渲染批次不存在");
        return batch;
    }
    private StoreListingExport requireExport(long id) {
        StoreListingExport export = exportMapper.selectById(id);
        if (export == null || !Objects.equals(export.getOwnerId(), owner())) throw new ServiceException("上架表格不存在");
        return export;
    }
    private ExportView view(StoreListingExport export) {
        return new ExportView(export.getId(), export.getBatchId(), export.getShopId(), export.getListingTemplateId(),
            export.getStatus(), export.getErrorMessage(), "上架表格-" + (export.getBatchId() == null ? "原图-" + export.getId() : export.getBatchId()) + "-" + export.getShopId() + ".xlsx");
    }
    private static long owner() { return Long.parseLong(LoginHelper.getUserIdStr()); }

    private static final class ProfileSettings {
        private static final List<String> FIELDS = List.of("required", "material", "hot", "holiday", "scene");
        private final Map<String, String> selectedWords = new HashMap<>();
        private int previewIndex, skuStart, skuDigits;
        private String skuSeparator, detailSeparator, carouselScope;

        static ProfileSettings parse(String json) {
            if (json == null || json.isBlank()) throw new ServiceException("请先保存上架表标题词库和生成配置");
            Map<?, ?> values = JsonUtils.parseObject(json, Map.class);
            if (values == null || !"en".equals(values.get("titleLanguage")) || !"image".equals(values.get("keywordMode")))
                throw new ServiceException("上架表仅支持英文词库和原图关键词模式");
            ProfileSettings settings = new ProfileSettings();
            Map<?, ?> words = values.get("words") instanceof Map<?, ?> map ? map : Map.of();
            Map<?, ?> selected = values.get("selected") instanceof Map<?, ?> map ? map : Map.of();
            for (String field : FIELDS) {
                List<?> options = words.get(field) instanceof List<?> list ? list : List.of();
                if (options.isEmpty() || options.size() > 20) throw new ServiceException("标题词库每类需要 1 至 20 个词条");
                int index = number(selected.get(field), "标题词库选项无效");
                if ("holiday".equals(field) && index == -1) { settings.selectedWords.put(field, ""); continue; }
                if (index < 0 || index >= options.size() || !(options.get(index) instanceof Map<?, ?> option) ||
                    !(option.get("en") instanceof String text) || text.isBlank() || text.length() > 160)
                    throw new ServiceException("标题词库选项无效: " + field);
                settings.selectedWords.put(field, text.trim());
            }
            settings.previewIndex = number(values.get("previewIndex"), "预览图序号无效");
            settings.skuStart = number(values.get("skuStart"), "SKU 起始序号无效");
            settings.skuDigits = number(values.get("skuDigits"), "SKU 位数无效");
            settings.skuSeparator = Objects.toString(values.get("skuSeparator"), "");
            settings.detailSeparator = Objects.toString(values.get("detailSeparator"), "");
            settings.carouselScope = Objects.toString(values.get("carouselScope"), "");
            if (settings.previewIndex < 1 || settings.previewIndex > 10 || settings.skuStart < 1 || settings.skuStart > 999999 ||
                settings.skuDigits < 1 || settings.skuDigits > 6 || !Set.of("", "-", "_").contains(settings.skuSeparator) ||
                !Set.of("｜", "|").contains(settings.detailSeparator) || !Set.of("spu", "both").contains(settings.carouselScope))
                throw new ServiceException("上架表生成参数无效");
            return settings;
        }
        String title(String keyword) {
            List<String> parts = new ArrayList<>();
            for (String field : List.of("required", "material")) parts.add(selectedWords.get(field));
            parts.add(keyword);
            for (String field : List.of("hot", "holiday", "scene")) parts.add(selectedWords.get(field));
            String title = String.join(", ", parts.stream().filter(part -> part != null && !part.isBlank()).toList());
            if (title.length() > 500) throw new ServiceException("商品标题超过 500 字符");
            return title;
        }
        StoreListingWorkbook.Profile workbookProfile() {
            return new StoreListingWorkbook.Profile(previewIndex, skuStart, skuDigits, skuSeparator, detailSeparator, carouselScope);
        }
        private static int number(Object value, String error) {
            if (!(value instanceof Number number) || number.doubleValue() != number.intValue()) throw new ServiceException(error);
            return number.intValue();
        }
    }
}
