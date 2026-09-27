package org.dromara.order.store.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.utils.IdGeneratorUtil;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.image.domain.ImageAsset;
import org.dromara.image.mapper.ImageAssetMapper;
import org.dromara.image.storage.ImageStorage;
import org.dromara.order.store.domain.*;
import org.dromara.order.store.mapper.*;
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

    @Value("${store.listing.public-base-url:}")
    private String publicBaseUrl;

    public record ExportView(Long id, Long batchId, Long shopId, Long listingTemplateId, String status,
                             String errorMessage, String filename) {}
    public record GenerateRequest(Long batchId, Long shopId, Long listingTemplateId) {}

    public List<ExportView> list(Long batchId, Long shopId, Long categoryId) {
        if (batchId != null) requireBatch(batchId);
        var query = Wrappers.<StoreListingExport>lambdaQuery().eq(StoreListingExport::getOwnerId, owner());
        if (batchId != null) query.eq(StoreListingExport::getBatchId, batchId);
        if (shopId != null) query.eq(StoreListingExport::getShopId, shopId);
        if (categoryId != null) query.inSql(StoreListingExport::getBatchId,
            "SELECT id FROM store_render_batch WHERE category_id=" + categoryId);
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
                existing.setStatus("PENDING"); existing.setErrorMessage(null); exportMapper.updateById(existing);
            }
            return view(existing);
        }
        if (!Objects.equals(batch.getShopId(), shop.getId()) || !Objects.equals(batch.getListingTemplateId(), template.getId()))
            throw new ServiceException("该批次未绑定此店铺表格");
        StoreListingExport result = new StoreListingExport();
        result.setId(Long.parseLong(IdGeneratorUtil.nextId())); result.setOwnerId(owner()); result.setBatchId(batch.getId());
        result.setShopId(shop.getId()); result.setListingTemplateId(template.getId()); result.setStatus("PENDING");
        try { exportMapper.insert(result); } catch (DuplicateKeyException duplicate) {
            return view(exportMapper.selectOne(Wrappers.<StoreListingExport>lambdaQuery().eq(StoreListingExport::getBatchId, batch.getId()).eq(StoreListingExport::getShopId, shop.getId())));
        }
        return view(result);
    }

    public ExportView retry(long id) {
        StoreListingExport export = requireExport(id);
        if (!Set.of("FAILED", "COMPLETED").contains(export.getStatus())) throw new ServiceException("当前上架表格不能重新生成");
        export.setStatus("PENDING"); export.setErrorMessage(null); exportMapper.updateById(export);
        return view(export);
    }

    public ImageStorage.LocalFile download(long id) {
        StoreListingExport export = requireExport(id);
        if (!"COMPLETED".equals(export.getStatus()) || export.getOssKey() == null) throw new ServiceException("上架表格尚未生成");
        return storage.open(export.getOssKey());
    }

    public ImageStorage.LocalFile publicResult(String key) {
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
        StoreRenderBatch batch = batchMapper.selectById(export.getBatchId());
        if (batch == null || !"COMPLETED".equals(batch.getStatus())) throw new ServiceException("渲染尚未全部完成");
        StoreListingTemplate template = templateMapper.selectById(export.getListingTemplateId());
        if (template == null || !Objects.equals(template.getOwnerId(), export.getOwnerId()) ||
            !Objects.equals(template.getShopId(), export.getShopId()) || !Objects.equals(template.getCategoryId(), batch.getCategoryId()) ||
            (export.getListingProfileJson() == null && !Objects.equals(batch.getListingTemplateId(), template.getId())))
            throw new ServiceException("上架表格已删除或与批次不匹配");
        String configuredBase = Objects.toString(publicBaseUrl, "").trim();
        String profileJson = export.getListingProfileJson() != null ? export.getListingProfileJson() :
            batch.getListingProfileJson() != null ? batch.getListingProfileJson() : template.getConfigJson();
        ProfileSettings settings = ProfileSettings.parse(profileJson);
        Map<?, ?> keywordSnapshot = export.getListingKeywordsJson() == null ? Map.of() : JsonUtils.parseObject(export.getListingKeywordsJson(), Map.class);
        Path output = Files.createTempFile("store-listing-", ".xlsx");
        try (ImageStorage.LocalFile original = storage.open(template.getOssKey());
             StoreListingWorkbook workbook = StoreListingWorkbook.open(Files.newInputStream(original.path()))) {
            List<StoreRenderItem> items = itemMapper.selectList(Wrappers.<StoreRenderItem>lambdaQuery()
                .eq(StoreRenderItem::getBatchId, batch.getId()).orderByAsc(StoreRenderItem::getId));
            if (items.size() != batch.getSelectedCount() || items.stream().anyMatch(item ->
                !Set.of("SUCCEEDED", "SKIPPED").contains(item.getStatus()) || item.getResultOssKey() == null))
                throw new ServiceException("渲染结果不完整，不能生成上架表格");
            LinkedHashMap<Long, List<StoreRenderItem>> grouped = new LinkedHashMap<>();
            for (StoreRenderItem item : items) grouped.computeIfAbsent(item.getImageId(), ignored -> new ArrayList<>()).add(item);
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
                if (image == null || !Objects.equals(image.getCreateBy(), export.getOwnerId())) throw new ServiceException("原图不存在或无权限: " + group.getKey());
                String keyword = Objects.toString(keywordSnapshot.get(String.valueOf(image.getId())), "").trim();
                if (!keyword.matches("(?s).*[A-Za-z].*") || keyword.matches("(?i).*\\.(jpg|jpeg|png|webp)$"))
                    throw new ServiceException("原图 " + group.getKey() + " 缺少侵权检测 AI 英文描述关键词快照");
                if (group.getValue().size() != templateCount) throw new ServiceException("原图 " + group.getKey() + " 的套图数量不完整");
                List<String> urls = group.getValue().stream().map(item -> {
                    String key = item.getResultOssKey();
                    if (!key.matches("store/results/" + export.getOwnerId() + "/[0-9a-f-]+\\.(jpg|jpeg|png|webp)"))
                        throw new ServiceException("成品图片地址无效");
                    return publicResultUrl(configuredBase, key);
                }).toList();
                if (settings.previewIndex > urls.size() || urls.size() > workbook.carouselCount())
                    throw new ServiceException("预览图序号或轮播图列与套图数量不匹配");
                products.add(new StoreListingWorkbook.Product(String.valueOf(group.getKey()), settings.title(keyword), urls));
            }
            workbook.fill(products, settings.workbookProfile());
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
            export.getStatus(), export.getErrorMessage(), "上架表格-" + export.getBatchId() + "-" + export.getShopId() + ".xlsx");
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
                throw new ServiceException("上架表仅支持英文标题和原图关键词");
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
            if (title.length() > 500) throw new ServiceException("商品英文标题超过 500 字符");
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
