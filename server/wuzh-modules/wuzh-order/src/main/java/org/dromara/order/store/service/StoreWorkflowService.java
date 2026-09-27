package org.dromara.order.store.service;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.utils.IdGeneratorUtil;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.image.domain.ImageAsset;
import org.dromara.image.domain.ImageStatus;
import org.dromara.image.domain.InfringementScanItem;
import org.dromara.image.service.InfringementService;
import org.dromara.image.storage.ImageStorage;
import org.dromara.order.store.domain.*;
import org.dromara.order.store.mapper.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class StoreWorkflowService {
    private static final int DEFAULT_CLAIM_LIMIT = 8;
    private static final int LEASE_SECONDS = 180;
    private final StoreShopMapper shopMapper;
    private final StoreCalibrationMapper calibrationMapper;
    private final StoreTemplateGroupMapper groupMapper;
    private final StoreTemplateMapper templateMapper;
    private final StoreRenderBatchMapper batchMapper;
    private final StoreRenderItemMapper itemMapper;
    private final StoreListingTemplateMapper listingTemplateMapper;
    private final StoreListingExportMapper listingExportMapper;
    private final org.dromara.image.mapper.ImageAssetMapper imageMapper;
    private final org.dromara.image.mapper.ImageCategoryMapper categoryMapper;
    private final InfringementService infringementService;
    private final ImageStorage storage;
    private final StoreObjectStorage storeStorage;
    private final JdbcTemplate jdbcTemplate;
    private final StoreReferenceService references;

    public List<StoreDtos.CategoryView> catalog() {
        return categoryMapper.selectList(Wrappers.<org.dromara.image.domain.ImageCategory>lambdaQuery().eq(org.dromara.image.domain.ImageCategory::getStatus, "0").orderByAsc(org.dromara.image.domain.ImageCategory::getSortOrder))
            .stream().map(c -> new StoreDtos.CategoryView(c.getId(), c.getName(), c.getSortOrder(), c.getStatus())).toList();
    }

    public List<StoreDtos.ShopView> shops() {
        return shopMapper.selectList(scope(StoreShop.class)).stream()
            .map(s -> new StoreDtos.ShopView(s.getId(), s.getName(), s.getPlatform(), s.getStatus(), s.getConfigJson(), s.getShopCode())).toList();
    }

    public List<StoreDtos.CalibrationView> calibrations() {
        return calibrationMapper.selectList(scope(StoreCalibration.class).orderByDesc("update_time", "id"))
            .stream().map(this::calibrationView).toList();
    }

    @Transactional
    public StoreDtos.CalibrationView saveCalibration(StoreDtos.CalibrationRequest request, Long id) {
        long owner = owner();
        StoreCalibration calibration = id == null ? new StoreCalibration() : calibrationMapper.selectById(id);
        if (calibration == null || (id != null && !Objects.equals(calibration.getOwnerId(), owner)))
            throw new ServiceException("校准记录不存在");
        String previousKey = id == null ? null : calibration.getBackgroundOssKey();
        List<StoreTemplate> linked = previousKey == null ? List.of() : linkedTemplates(owner, previousKey);
        if (!linked.isEmpty() && !Objects.equals(calibration.getCategoryId(), request.categoryId()))
            throw new ServiceException("背景已加入模板组，不能修改类目");
        checkGroupsIdle(linked);
        if (request.name().trim().length() > 80 || categoryMapper.selectById(request.categoryId()) == null)
            throw new ServiceException("校准名称或类目无效");
        String configJson = request.calibrationConfigJson();
        if (configJson.length() > 64000) throw new ServiceException("背景校准配置无效");
        Map<?, ?> config;
        try { config = JsonUtils.parseObject(configJson, Map.class); }
        catch (Exception ignored) { throw new ServiceException("背景校准配置无效"); }
        if (config == null || !Objects.equals(config.get("schemaVersion"), 2) ||
            !Objects.equals(config.get("engine"), "declarative-canvas-v1") ||
            !(config.get("regions") instanceof List<?> regions) || regions.isEmpty() || regions.size() > 8)
            throw new ServiceException("背景校准配置无效");
        String imagePattern = "store/templates/" + owner + "/[0-9a-f-]+\\.(jpg|jpeg|png|webp)";
        if (!request.backgroundOssKey().matches(imagePattern) ||
            (request.maskOssKey() != null && !request.maskOssKey().matches("store/templates/" + owner + "/[0-9a-f-]+\\.png")))
            throw new ServiceException("校准图片无效");
        if (id == null) {
            calibration.setId(nextId()); calibration.setOwnerId(owner); calibration.setVersion(1);
        } else calibration.setVersion(calibration.getVersion() + 1);
        calibration.setName(request.name().trim()); calibration.setCategoryId(request.categoryId());
        calibration.setCalibrationConfigJson(configJson); calibration.setBackgroundOssKey(request.backgroundOssKey());
        calibration.setMaskOssKey(request.maskOssKey());
        calibration.setStatus("PUBLISHED");
        if (id == null) calibrationMapper.insert(calibration); else calibrationMapper.updateById(calibration);
        for (StoreTemplate template : linked) {
            template.setName(calibration.getName());
            template.setOssKey(calibration.getBackgroundOssKey());
            template.setWidth(((Number) config.get("width")).intValue());
            template.setHeight(((Number) config.get("height")).intValue());
            Map<String, Object> templateSettings = new LinkedHashMap<>();
            templateConfig(template).forEach((key, value) -> templateSettings.put(String.valueOf(key), value));
            templateSettings.put("declaration", config);
            templateSettings.put("maskOssKey", calibration.getMaskOssKey());
            template.setTemplateConfigJson(JsonUtils.toJsonString(templateSettings));
            templateMapper.updateById(template);
        }
        linked.stream().map(StoreTemplate::getGroupId).distinct().forEach(this::syncGroupBackground);
        return calibrationView(calibration);
    }

    @Transactional
    public void deleteCalibration(long id) {
        StoreCalibration calibration = calibrationMapper.selectById(id);
        if (calibration == null || !Objects.equals(calibration.getOwnerId(), owner()) || !"PUBLISHED".equals(calibration.getStatus()))
            throw new ServiceException("已发布背景不存在");
        List<StoreTemplate> linked = linkedTemplates(calibration.getOwnerId(), calibration.getBackgroundOssKey());
        checkGroupsIdle(linked);
        calibration.setStatus("DELETED");
        calibrationMapper.updateById(calibration);
        for (StoreTemplate template : linked) {
            template.setStatus("DELETED");
            templateMapper.updateById(template);
        }
        linked.stream().map(StoreTemplate::getGroupId).distinct().forEach(this::syncGroupBackground);
    }

    private List<StoreTemplate> linkedTemplates(long ownerId, String key) {
        return templateMapper.selectList(Wrappers.<StoreTemplate>lambdaQuery().eq(StoreTemplate::getOwnerId, ownerId)
            .eq(StoreTemplate::getOssKey, key).eq(StoreTemplate::getStatus, "ACTIVE"));
    }

    private void checkGroupsIdle(List<StoreTemplate> linked) {
        for (Long groupId : linked.stream().map(StoreTemplate::getGroupId).distinct().toList())
            if (batchMapper.selectCount(Wrappers.<StoreRenderBatch>query().eq("template_group_id", groupId)
                .in("status", "CREATED", "RUNNING")) > 0)
                throw new ServiceException("模板组正在渲染，完成后才能编辑背景");
    }

    private void syncGroupBackground(long groupId) {
        StoreTemplateGroup group = groupMapper.selectById(groupId);
        List<StoreTemplate> active = templateMapper.selectList(Wrappers.<StoreTemplate>lambdaQuery()
            .eq(StoreTemplate::getGroupId, groupId).eq(StoreTemplate::getStatus, "ACTIVE"));
        if (active.isEmpty()) group.setStatus("DELETED");
        else {
            StoreTemplate first = active.stream().min(Comparator.comparingInt(this::templateOrder)).orElseThrow();
            Map<?, ?> settings = templateConfig(first);
            if (settings.get("declaration") != null) group.setCalibrationConfigJson(JsonUtils.toJsonString(settings.get("declaration")));
            group.setMaskOssKey((String) settings.get("maskOssKey"));
        }
        groupMapper.updateById(group);
    }

    private StoreDtos.CalibrationView calibrationView(StoreCalibration calibration) {
        String mask = calibration.getMaskOssKey();
        return new StoreDtos.CalibrationView(calibration.getId(), calibration.getName(), calibration.getCategoryId(),
            calibration.getCalibrationConfigJson(), calibration.getBackgroundOssKey(),
            storeStorage.url(calibration.getBackgroundOssKey(), "image/*"), mask,
            mask == null ? null : storeStorage.url(mask, "image/png"), calibration.getStatus(), calibration.getVersion());
    }

    @Transactional
    public StoreDtos.ShopView saveShop(StoreDtos.ShopRequest request, Long id) {
        long owner = owner();
        StoreShop shop = id == null ? new StoreShop() : shopMapper.selectById(id);
        if (shop == null || (id != null && !Objects.equals(shop.getOwnerId(), owner))) throw new ServiceException("店铺不存在");
        String name = request.name().trim();
        if (name.length() > 80) throw new ServiceException("店铺名称无效");
        shop.setName(name);
        if (id == null) {
            String code = Objects.toString(request.shopCode(), "").trim();
            String operator = references.operatorName(request.operatorUserId());
            String platform = references.optionName("PLATFORM", request.platformId());
            List<Long> siteIds = request.siteIds() == null ? List.of() : request.siteIds().stream().filter(Objects::nonNull).distinct().toList();
            List<String> sites = siteIds.stream().map(siteId -> references.optionName("SITE", siteId)).toList();
            String notes = Objects.toString(request.notes(), "").trim();
            List<Long> categories = request.categoryIds() == null ? List.of() : request.categoryIds().stream().filter(Objects::nonNull).distinct().toList();
            if (!code.matches("[A-Za-z0-9_-]{1,80}") || operator == null || platform == null || notes.length() > 500 ||
                siteIds.isEmpty() || siteIds.size() != (request.siteIds() == null ? 0 : request.siteIds().size()) || sites.contains(null) ||
                categories.isEmpty() || categories.size() != (request.categoryIds() == null ? 0 : request.categoryIds().size()) ||
                categoryMapper.selectCount(Wrappers.<org.dromara.image.domain.ImageCategory>query()
                    .in("id", categories).eq("status", "0")) != categories.size())
                throw new ServiceException("店铺 ID、负责人、站点或经营类目无效");
            shop.setId(nextId()); shop.setOwnerId(owner); shop.setStatus("ACTIVE"); shop.setShopCode(code);
            shop.setPlatform(platform);
            shop.setConfigJson(JsonUtils.toJsonString(Map.of("operatorUserId", request.operatorUserId(), "operator", operator,
                "platformId", request.platformId(), "siteIds", siteIds, "sites", sites, "categoryIds", categories, "notes", notes)));
            try { shopMapper.insert(shop); } catch (DuplicateKeyException duplicate) { throw new ServiceException("店铺 ID 已存在"); }
        } else {
            if (request.platform() == null || request.platform().isBlank() || request.platform().trim().length() > 40)
                throw new ServiceException("店铺平台无效");
            shop.setPlatform(request.platform().trim());
            shop.setConfigJson(request.configJson());
            shopMapper.updateById(shop);
        }
        return new StoreDtos.ShopView(shop.getId(), shop.getName(), shop.getPlatform(), shop.getStatus(), shop.getConfigJson(), shop.getShopCode());
    }

    public List<StoreDtos.TemplateGroupView> groups(Long categoryId, boolean includeDeleted) {
        var q = scope(StoreTemplateGroup.class);
        if (categoryId != null) q.eq("category_id", categoryId);
        if (!includeDeleted) q.ne("status", "DELETED");
        q.orderByDesc("update_time", "id");
        return groupMapper.selectList(q).stream().map(this::groupView).toList();
    }

    public StoreDtos.TemplateGroupView group(long id) { return groupView(requireGroup(id)); }
    public List<StoreDtos.TemplateView> templates(Long groupId, Long categoryId) {
        var q = Wrappers.<StoreTemplate>lambdaQuery().eq(StoreTemplate::getStatus, "ACTIVE");
        if (groupId != null) q.eq(StoreTemplate::getGroupId, groupId); if (categoryId != null) q.eq(StoreTemplate::getCategoryId, categoryId);
        q.eq(StoreTemplate::getOwnerId, owner());
        return templateMapper.selectList(q).stream().sorted(Comparator.comparingInt(this::templateOrder).thenComparing(StoreTemplate::getId)).map(t -> new StoreDtos.TemplateView(t.getId(), t.getName(), t.getOssKey(), storeStorage.url(t.getOssKey(), "image/*"), t.getTemplateConfigJson(), t.getWidth(), t.getHeight())).toList();
    }



    @Transactional
    public StoreDtos.TemplateGroupView saveGroup(StoreDtos.TemplateGroupRequest request, Long id) {
        long owner = owner();
        StoreTemplateGroup group = id == null ? new StoreTemplateGroup() : requireGroup(id);
        if (id != null && !Objects.equals(group.getOwnerId(), owner)) throw new ServiceException("模板组不存在");
        if (id == null) { group.setId(nextId()); group.setOwnerId(owner); group.setStatus("ACTIVE"); }
        List<StoreDtos.TemplateInput> inputs = Optional.ofNullable(request.templates()).orElse(List.of());
        if (inputs.isEmpty() || inputs.size() > 8) throw new ServiceException("模板组需要 1 至 8 张背景图片");
        if (request.name().trim().length() > 80 || categoryMapper.selectById(request.categoryId()) == null)
            throw new ServiceException("模板组名称或类目无效");
        String calibrationJson = request.calibrationConfigJson();
        if (calibrationJson == null || calibrationJson.length() > 64000) throw new ServiceException("背景校准配置无效");
        Map<?, ?> calibration;
        try { calibration = JsonUtils.parseObject(calibrationJson, Map.class); }
        catch (Exception ignored) { throw new ServiceException("背景校准配置无效"); }
        if (calibration == null || !Objects.equals(calibration.get("schemaVersion"), 2) ||
            !Objects.equals(calibration.get("engine"), "declarative-canvas-v1") ||
            !(calibration.get("regions") instanceof List<?> regions) || regions.isEmpty() || regions.size() > 8)
            throw new ServiceException("背景校准配置无效");
        if (request.maskOssKey() != null && !request.maskOssKey().matches("store/templates/" + owner + "/[0-9a-f-]+\\.png"))
            throw new ServiceException("校准蒙版无效");
        if (id != null && batchMapper.selectCount(Wrappers.<StoreRenderBatch>lambdaQuery().eq(StoreRenderBatch::getTemplateGroupId, id).in(StoreRenderBatch::getStatus, "CREATED", "RUNNING")) > 0)
            throw new ServiceException("模板组正在渲染，完成后才能编辑");
        Set<String> keys = new HashSet<>();
        for (StoreDtos.TemplateInput input : inputs) {
            if (input == null || input.ossKey() == null || !input.ossKey().matches("store/templates/" + group.getOwnerId() + "/[0-9a-f-]+\\.(jpg|jpeg|png|webp)") || !keys.add(input.ossKey()))
                throw new ServiceException("模板图片无效或重复");
        }
        group.setName(request.name().trim()); group.setCategoryId(request.categoryId());
        group.setCalibrationConfigJson(request.calibrationConfigJson());
        group.setMaskOssKey(request.maskOssKey());
        if (id == null) groupMapper.insert(group); else groupMapper.updateById(group);
        Map<String, StoreTemplate> previous = new HashMap<>();
        if (id != null) templateMapper.selectList(Wrappers.<StoreTemplate>lambdaQuery().eq(StoreTemplate::getGroupId, id)).forEach(t -> previous.put(t.getOssKey(), t));
        long groupOwner = group.getOwnerId();
        for (int order = 0; order < inputs.size(); order++) {
            StoreDtos.TemplateInput input = inputs.get(order);
            StoreTemplate t = previous.remove(input.ossKey());
            boolean isNew = t == null;
            if (isNew) { t = new StoreTemplate(); t.setId(nextId()); t.setOwnerId(groupOwner); t.setGroupId(group.getId()); }
            t.setCategoryId(group.getCategoryId()); t.setName(input.name()); t.setOssKey(input.ossKey()); t.setImageUrl(null);
            t.setTemplateConfigJson(templateConfig(input, groupOwner, group.getCategoryId(), order)); t.setWidth(input.width()); t.setHeight(input.height()); t.setStatus("ACTIVE");
            if (isNew) templateMapper.insert(t); else templateMapper.updateById(t);
        }
        previous.values().forEach(t -> { t.setStatus("DELETED"); templateMapper.updateById(t); });
        return groupView(group);
    }

    private String templateConfig(StoreDtos.TemplateInput input, long owner, long categoryId, int order) {
        Map<String, Object> config;
        try { config = new LinkedHashMap<>(JsonUtils.parseObject(Optional.ofNullable(input.templateConfigJson()).orElse("{}"), Map.class)); }
        catch (Exception ignored) { throw new ServiceException("模板配置无效"); }
        Object calibrationId = config.remove("calibrationId");
        if (calibrationId != null) {
            StoreCalibration calibration;
            try { calibration = calibrationMapper.selectById(Long.parseLong(String.valueOf(calibrationId))); }
            catch (NumberFormatException ignored) { throw new ServiceException("校准背景不存在"); }
            if (calibration == null || !Objects.equals(calibration.getOwnerId(), owner) ||
                !Objects.equals(calibration.getCategoryId(), categoryId) || !"PUBLISHED".equals(calibration.getStatus()) ||
                !Objects.equals(calibration.getBackgroundOssKey(), input.ossKey())) throw new ServiceException("校准背景不存在或类目不符");
            config.put("declaration", JsonUtils.parseObject(calibration.getCalibrationConfigJson(), Map.class));
            config.put("maskOssKey", calibration.getMaskOssKey());
        }
        Object maskKey = config.get("maskOssKey");
        if (maskKey != null && (!(maskKey instanceof String key) || !key.matches("store/templates/" + owner + "/[0-9a-f-]+\\.png")))
            throw new ServiceException("校准蒙版无效");
        config.put("sortOrder", order);
        return JsonUtils.toJsonString(config);
    }

    private Map<?, ?> templateConfig(StoreTemplate template) {
        return templateConfig(template.getTemplateConfigJson());
    }

    private Map<?, ?> templateConfig(String json) {
        try { return JsonUtils.parseObject(Optional.ofNullable(json).orElse("{}"), Map.class); }
        catch (Exception ignored) { return Map.of(); }
    }

    private int templateOrder(StoreTemplate template) {
        Object order = templateConfig(template).get("sortOrder");
        return order instanceof Number number ? number.intValue() : Integer.MAX_VALUE;
    }

    @Transactional
    public void deleteGroup(long id) { setGroupStatus(id, "DELETED"); }

    @Transactional
    public void setGroupStatus(long id, String status) {
        if (!Set.of("ACTIVE", "INACTIVE", "DELETED").contains(status)) throw new ServiceException("模板组状态无效");
        StoreTemplateGroup group = groupMapper.selectById(id);
        if (group == null) throw new ServiceException("模板组不存在");
        checkOwner(group.getOwnerId());
        if ("DELETED".equals(group.getStatus()) && !"INACTIVE".equals(status)) throw new ServiceException("请先恢复模板组");
        group.setStatus(status);
        groupMapper.updateById(group);
    }

    public ImageStorage.UploadPolicy uploadPolicy() {
        return storage.signUploadPolicy("store/templates/" + owner() + "/", java.time.Duration.ofMinutes(30));
    }

    public StoreDtos.UploadView upload(MultipartFile file) {
        return storeStorage.save(owner(), "templates", file);
    }
    public StoreDtos.UploadView uploadListing(MultipartFile file) { return storeStorage.save(owner(), "listing", file); }

    @Transactional
    public StoreDtos.RenderBatchView createBatch(StoreDtos.RenderBatchRequest request) {
        long owner = owner();
        StoreRenderBatch previous = batchMapper.selectOne(Wrappers.<StoreRenderBatch>lambdaQuery().eq(StoreRenderBatch::getOwnerId, owner).eq(StoreRenderBatch::getIdempotencyKey, request.idempotencyKey()).last("limit 1"));
        if (previous != null) return batchView(previous);
        StoreTemplateGroup group = requireGroup(request.templateGroupId());
        checkOwner(group.getOwnerId());
        if (!"ACTIVE".equals(group.getStatus())) throw new ServiceException("模板组未启用");
        if (request.destinations() != null && (request.shopId() != null || request.listingTemplateId() != null))
            throw new ServiceException("请使用一种店铺表格配置方式");
        List<StoreDtos.RenderDestinationRequest> destinations = request.destinations() != null ? request.destinations() :
            request.listingTemplateId() == null ? List.of() : List.of(new StoreDtos.RenderDestinationRequest(request.shopId(), request.listingTemplateId(), null));
        if (destinations.size() > 20 || destinations.stream().anyMatch(destination -> destination == null || destination.shopId() == null || destination.listingTemplateId() == null) ||
            destinations.stream().map(StoreDtos.RenderDestinationRequest::shopId).distinct().count() != destinations.size())
            throw new ServiceException("目标店铺重复或超过 20 家");
        if (request.shopId() != null && request.listingTemplateId() == null) requireShop(request.shopId());
        List<StoreListingTemplate> listings = new ArrayList<>();
        List<String> listingProfiles = new ArrayList<>();
        for (StoreDtos.RenderDestinationRequest destination : destinations) {
            StoreShop shop = requireShop(destination.shopId());
            StoreListingTemplate listing = requireListingTemplate(destination.listingTemplateId());
            if (!Objects.equals(shop.getOwnerId(), group.getOwnerId()) || !Objects.equals(listing.getShopId(), shop.getId()) ||
                !Objects.equals(listing.getCategoryId(), request.categoryId()) || !Objects.equals(listing.getOwnerId(), group.getOwnerId()))
                throw new ServiceException("上架表格与店铺、类目或模板组不匹配");
            if (listing.getConfigJson() == null) throw new ServiceException("请先保存所选上架模板表格的生成配置");
            listings.add(listing);
            listingProfiles.add(listingProfile(listing.getConfigJson(), destination.titleSelection()));
        }
        List<StoreTemplate> templates = new ArrayList<>(templateMapper.selectList(Wrappers.<StoreTemplate>lambdaQuery().eq(StoreTemplate::getGroupId, group.getId()).eq(StoreTemplate::getStatus, "ACTIVE")));
        templates.sort(Comparator.comparingInt(this::templateOrder).thenComparing(StoreTemplate::getId));
        if (templates.isEmpty()) throw new ServiceException("模板组没有可用模板");
        if (request.imageIds().size() > 10000) throw new ServiceException("单批次最多选择10000张图片");
        List<Long> imageIds = request.imageIds().stream().distinct().toList();
        long validImages = 0;
        for (int start = 0; start < imageIds.size(); start += 500)
            validImages += imageMapper.selectCount(Wrappers.<ImageAsset>lambdaQuery().in(ImageAsset::getId, imageIds.subList(start, Math.min(start + 500, imageIds.size()))).eq(ImageAsset::getStatus, ImageStatus.APPROVED.name()).eq(ImageAsset::getCategoryId, request.categoryId()).eq(ImageAsset::getCreateBy, owner));
        if (validImages != imageIds.size()) throw new ServiceException("存在无权限或未审核图片");
        long lowRiskImages = 0;
        for (int start = 0; start < imageIds.size(); start += 500)
            lowRiskImages += imageMapper.selectCount(Wrappers.<ImageAsset>lambdaQuery().in(ImageAsset::getId, imageIds.subList(start, Math.min(start + 500, imageIds.size()))).eq(ImageAsset::getStatus, ImageStatus.APPROVED.name()).eq(ImageAsset::getCategoryId, request.categoryId()).eq(ImageAsset::getCreateBy, owner)
                .apply("infringement_scan_item_id IN (SELECT id FROM infringement_scan_item WHERE status = 'SUCCESS' AND risk_level = 'LOW')"));
        if (lowRiskImages != imageIds.size()) throw new ServiceException("仅低风险图片可以渲染，请重新选择图片");
        Map<String, String> keywordSnapshot = new HashMap<>();
        if (!destinations.isEmpty()) for (int start = 0; start < imageIds.size(); start += 500) {
            List<ImageAsset> images = imageMapper.selectByIds(imageIds.subList(start, Math.min(start + 500, imageIds.size())));
            List<Long> scanIds = images.stream().map(ImageAsset::getInfringementScanItemId).filter(Objects::nonNull).distinct().toList();
            Map<Long, InfringementScanItem> scans = scanIds.isEmpty() ? Map.of() : infringementService.currentResults(scanIds);
            for (ImageAsset image : images) {
                Long scanId = image.getInfringementScanItemId();
                String keywords = InfringementService.aiListingKeywords(scanId == null ? null : scans.get(scanId));
                if (keywords == null)
                    throw new ServiceException("原图 " + image.getId() + " 缺少侵权检测 AI 英文描述关键词");
                keywordSnapshot.put(String.valueOf(image.getId()), keywords);
            }
        }
        if (!destinations.isEmpty() && keywordSnapshot.size() != imageIds.size()) throw new ServiceException("原图关键词读取不完整");
        if (!Objects.equals(request.categoryId(), group.getCategoryId())) throw new ServiceException("图片类目与模板组不一致");
        String format = request.format() == null || request.format().isBlank() ? "jpg" : request.format().toLowerCase(Locale.ROOT);
        if (!Set.of("jpg", "jpeg", "png").contains(format)) throw new ServiceException("仅支持 JPG、PNG 格式");
        int quality = request.quality() == null ? 90 : request.quality();
        if (quality < 60 || quality > 100) throw new ServiceException("图片质量需在 60 至 100 之间");
        Map<Long, String> hashes = new HashMap<>();
        for (StoreTemplate template : templates)
            hashes.put(template.getId(), sha256("renderer-1|" + group.getId() + "|" + template.getId() + "|" + template.getOssKey() + "|" + format + "|" + quality + "|" + Optional.ofNullable(group.getCalibrationConfigJson()).orElse("") + "|" + Optional.ofNullable(group.getMaskOssKey()).orElse("") + "|" + Optional.ofNullable(template.getTemplateConfigJson()).orElse("")));
        Map<String, StoreRenderItem> successes = new HashMap<>();
        for (int start = 0; start < imageIds.size(); start += 500) {
            var q = Wrappers.<StoreRenderItem>lambdaQuery().eq(StoreRenderItem::getOwnerId, owner).eq(StoreRenderItem::getTemplateGroupId, group.getId())
                .eq(StoreRenderItem::getStatus, "SUCCEEDED").in(StoreRenderItem::getImageId, imageIds.subList(start, Math.min(start + 500, imageIds.size())))
                .in(StoreRenderItem::getTemplateId, hashes.keySet()).in(StoreRenderItem::getRenderConfigHash, hashes.values()).orderByDesc(StoreRenderItem::getId);
            for (StoreRenderItem item : itemMapper.selectList(q)) successes.putIfAbsent(item.getTemplateId() + ":" + item.getImageId() + ":" + item.getRenderConfigHash(), item);
        }
        StoreRenderBatch batch = new StoreRenderBatch(); batch.setId(nextId()); batch.setOwnerId(owner); batch.setCategoryId(request.categoryId()); batch.setTemplateGroupId(group.getId());
        batch.setShopId(listings.isEmpty() ? request.shopId() : listings.get(0).getShopId());
        batch.setListingTemplateId(listings.isEmpty() ? null : listings.get(0).getId());
        batch.setStatus("CREATED"); batch.setIdempotencyKey(request.idempotencyKey());
        if (!listingProfiles.isEmpty()) batch.setListingProfileJson(listingProfiles.get(0));
        int total = 0, skipped = 0;
        batch.setSelectedCount(imageIds.size() * templates.size()); batch.setCompletedCount(0); batch.setFailedCount(0); batch.setSkippedCount(0); batchMapper.insert(batch);
        if (request.destinations() != null) for (int i = 0; i < listings.size(); i++) {
            StoreListingTemplate listing = listings.get(i);
            StoreListingExport export = new StoreListingExport();
            export.setId(nextId()); export.setOwnerId(owner); export.setBatchId(batch.getId());
            export.setShopId(listing.getShopId()); export.setListingTemplateId(listing.getId());
            export.setListingProfileJson(listingProfiles.get(i)); export.setListingKeywordsJson(JsonUtils.toJsonString(keywordSnapshot)); export.setStatus("WAITING");
            listingExportMapper.insert(export);
        }
        List<StoreRenderItem> items = new ArrayList<>(batch.getSelectedCount());
        for (Long imageId : imageIds) for (StoreTemplate template : templates) {
            total++;
            String hash = hashes.get(template.getId());
            StoreRenderItem existing = successes.get(template.getId() + ":" + imageId + ":" + hash);
            StoreRenderItem item = new StoreRenderItem(); item.setId(nextId()); item.setOwnerId(owner); item.setBatchId(batch.getId()); item.setTemplateGroupId(group.getId()); item.setTemplateId(template.getId()); item.setImageId(imageId); item.setRenderConfigHash(hash); item.setCalibrationConfigJson(group.getCalibrationConfigJson()); item.setMaskOssKey(templateConfig(template).containsKey("maskOssKey") ? (String) templateConfig(template).get("maskOssKey") : group.getMaskOssKey()); item.setTemplateConfigJson(template.getTemplateConfigJson()); item.setOutputFormat(format); item.setQuality(quality);
            if (existing != null && "SUCCEEDED".equals(existing.getStatus())) { item.setStatus("SKIPPED"); item.setResultOssKey(existing.getResultOssKey()); item.setResultUrl(existing.getResultUrl()); item.setResultSha256(existing.getResultSha256()); skipped++; } else item.setStatus("PENDING");
            items.add(item);
        }
        jdbcTemplate.batchUpdate("INSERT INTO store_render_item(id,owner_id,batch_id,template_group_id,template_id,image_id,render_config_hash,calibration_config_json,mask_oss_key,template_config_json,status,output_format,quality,result_oss_key,result_url,result_sha256,create_by,create_time,update_by,update_time) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW(),?,NOW())", items, 500, (ps, item) -> {
            ps.setLong(1,item.getId()); ps.setLong(2,owner); ps.setLong(3,batch.getId()); ps.setLong(4,group.getId()); ps.setLong(5,item.getTemplateId()); ps.setLong(6,item.getImageId());
            ps.setString(7,item.getRenderConfigHash()); ps.setString(8,item.getCalibrationConfigJson()); ps.setString(9,item.getMaskOssKey()); ps.setString(10,item.getTemplateConfigJson()); ps.setString(11,item.getStatus()); ps.setString(12,item.getOutputFormat()); ps.setInt(13,item.getQuality());
            ps.setString(14,item.getResultOssKey()); ps.setString(15,item.getResultUrl()); ps.setString(16,item.getResultSha256()); ps.setLong(17,owner); ps.setLong(18,owner);
        });
        batch.setSkippedCount(skipped); batch.setSelectedCount(total); batch.setStatus(skipped == total ? "COMPLETED" : "RUNNING"); batchMapper.updateById(batch);
        return batchView(batch);
    }

    private String listingProfile(String configJson, Map<String, Integer> overrides) {
        if (overrides == null || overrides.isEmpty()) return configJson;
        Map<?, ?> parsed = JsonUtils.parseObject(configJson, Map.class);
        if (parsed == null || !(parsed.get("words") instanceof Map<?, ?> words)) throw new ServiceException("上架表标题词库无效");
        Map<Object, Object> profile = new HashMap<>(parsed);
        Map<Object, Object> selected = parsed.get("selected") instanceof Map<?, ?> current ? new HashMap<>(current) : new HashMap<>();
        for (var choice : overrides.entrySet()) {
            String field = choice.getKey(); Integer index = choice.getValue();
            if (!Set.of("required", "material", "hot", "holiday", "scene").contains(field) ||
                !(words.get(field) instanceof List<?> options) || index == null ||
                ("holiday".equals(field) && index == -1 ? false : index < 0 || index >= options.size()))
                throw new ServiceException("标题词库选项无效");
            selected.put(field, index);
        }
        profile.put("selected", selected);
        return JsonUtils.toJsonString(profile);
    }

    @Transactional public StoreDtos.ListingTemplateView saveListingProfile(long id, StoreDtos.ListingProfileRequest request) {
        StoreListingTemplate template = requireListingTemplate(id);
        validateListingProfile(request.configJson());
        template.setConfigJson(request.configJson());
        listingTemplateMapper.updateById(template);
        return listingTemplateView(template);
    }

    private void validateListingProfile(String configJson) {
        try {
            if (configJson == null || configJson.length() > 64000 || JsonUtils.parseObject(configJson, Map.class) == null)
                throw new ServiceException("上架表生成配置无效");
        } catch (Exception ignored) { throw new ServiceException("上架表生成配置无效"); }
    }

    public List<StoreDtos.ListingTemplateView> listingTemplates(Long shopId, Long categoryId) {
        var q = scope(StoreListingTemplate.class); if (shopId != null) q.eq("shop_id", shopId); if (categoryId != null) q.eq("category_id", categoryId); q.eq("status", "ACTIVE");
        return listingTemplateMapper.selectList(q.orderByDesc("version_no", "id")).stream().map(this::listingTemplateView).toList();
    }
    @Transactional public StoreDtos.ListingTemplateView saveListingTemplate(StoreDtos.ListingTemplateRequest request, Long id) {
        if (id == null || request.configJson() != null) validateListingProfile(request.configJson());
        StoreShop shop = requireShop(request.shopId());
        long owner=owner(); StoreListingTemplate t=id==null?new StoreListingTemplate():listingTemplateMapper.selectById(id);
        if(t==null || (id!=null && !Objects.equals(t.getOwnerId(),owner))) throw new ServiceException("上架模板不存在");
        if (id != null && !Objects.equals(t.getOwnerId(), shop.getOwnerId())) throw new ServiceException("店铺与上架模板不属于同一用户");
        if (request.ossKey() == null || !request.ossKey().matches("store/listing/" + shop.getOwnerId() + "/[0-9a-f-]+\\.xlsx")) throw new ServiceException("上架表格文件无效");
        if (id != null && (!Objects.equals(t.getShopId(), request.shopId()) || !Objects.equals(t.getCategoryId(), request.categoryId()) || !Objects.equals(t.getOssKey(), request.ossKey())))
            throw new ServiceException("更换店铺、类目或 Excel 请上传新版本");
        if (id == null) {
            org.dromara.image.domain.ImageCategory category = categoryMapper.selectById(request.categoryId());
            if (category == null) throw new ServiceException("类目不存在");
            jdbcTemplate.queryForObject("SELECT id FROM store_shop WHERE id=? FOR UPDATE", Long.class, shop.getId());
            Integer version = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM store_listing_template WHERE owner_id=? AND shop_id=? AND category_id=?", Integer.class, owner, shop.getId(), request.categoryId());
            t.setId(nextId());t.setOwnerId(shop.getOwnerId());t.setStatus("ACTIVE");t.setVersionNo(version);
            t.setTemplateCode(shop.getName() + "(" + (shop.getShopCode() == null || shop.getShopCode().isBlank() ? shop.getId() : shop.getShopCode()) + ")_" + category.getName() + "_" + String.format(Locale.ROOT, "%03d", version));
        }
        t.setShopId(request.shopId());t.setCategoryId(request.categoryId());t.setName(request.name().trim());t.setOssKey(request.ossKey());t.setOriginalFilename(request.originalFilename());
        if (request.configJson() != null) t.setConfigJson(request.configJson());
        if(id==null) listingTemplateMapper.insert(t); else listingTemplateMapper.updateById(t);
        return listingTemplateView(t);
    }
    @Transactional public void deleteListingTemplate(long id){ StoreListingTemplate t=listingTemplateMapper.selectById(id); if(t==null) throw new ServiceException("上架模板不存在"); checkOwner(t.getOwnerId()); t.setStatus("DELETED"); listingTemplateMapper.updateById(t); }
    private StoreDtos.ListingTemplateView listingTemplateView(StoreListingTemplate t) { return new StoreDtos.ListingTemplateView(t.getId(),t.getShopId(),t.getCategoryId(),t.getName(),t.getOssKey(),t.getOriginalFilename(),t.getStatus(),t.getConfigJson(),t.getVersionNo(),t.getTemplateCode()); }

    public StoreDtos.PageView<StoreDtos.RenderBatchView> batches(String status, Long shopId, Long categoryId, int pageNum, int pageSize) {
        var q = Wrappers.<StoreRenderBatch>lambdaQuery().eq(StoreRenderBatch::getOwnerId, owner());
        if (status != null && !status.isBlank()) q.in(StoreRenderBatch::getStatus, Arrays.asList(status.split(",")));
        if (shopId != null) q.and(condition -> condition.eq(StoreRenderBatch::getShopId, shopId)
            .or().apply("id IN (SELECT batch_id FROM store_listing_export WHERE shop_id={0})", shopId));
        if (categoryId != null) q.eq(StoreRenderBatch::getCategoryId, categoryId);
        Page<StoreRenderBatch> page = batchMapper.selectPage(new Page<>(Math.max(1, pageNum), Math.max(1, Math.min(100, pageSize))), q.orderByDesc(StoreRenderBatch::getId));
        return new StoreDtos.PageView<>(page.getRecords().stream().map(this::batchView).toList(), page.getTotal());
    }
    public StoreDtos.RenderBatchView batch(long id) { return batchView(requireBatch(id)); }
    @Transactional public StoreDtos.RenderBatchView retryBatch(long id) {
        StoreRenderBatch batch = requireBatch(id);
        int retried = itemMapper.update(null, Wrappers.<StoreRenderItem>lambdaUpdate().eq(StoreRenderItem::getBatchId, id).eq(StoreRenderItem::getStatus, "FAILED")
            .set(StoreRenderItem::getStatus, "PENDING").set(StoreRenderItem::getErrorMessage, null).set(StoreRenderItem::getCompletedAt, null));
        if (retried == 0) return batchView(batch);
        batch.setFailedCount(0); batch.setStatus("RUNNING"); batchMapper.updateById(batch);
        return batchView(batch);
    }
    public StoreDtos.PageView<StoreRenderItem> results(Long batchId, int pageNum, int pageSize) { var q=Wrappers.<StoreRenderItem>lambdaQuery().eq(StoreRenderItem::getOwnerId, owner()); if(batchId!=null) q.eq(StoreRenderItem::getBatchId,batchId); Page<StoreRenderItem> page=itemMapper.selectPage(new Page<>(Math.max(1,pageNum),Math.max(1,Math.min(100,pageSize))), q.orderByDesc(StoreRenderItem::getId)); page.getRecords().stream().filter(item -> item.getResultOssKey() != null).forEach(item -> item.setResultUrl(storeStorage.url(item.getResultOssKey(), "image/*"))); return new StoreDtos.PageView<>(page.getRecords(),page.getTotal()); }

    public StoreDtos.PageView<StoreDtos.RenderGroupView> resultGroups(long batchId, int pageNum, int pageSize) {
        requireBatch(batchId);
        int size = Math.max(1, Math.min(50, pageSize));
        long total = jdbcTemplate.queryForObject("SELECT COUNT(DISTINCT image_id) FROM store_render_item WHERE batch_id=?", Long.class, batchId);
        List<Long> imageIds = jdbcTemplate.queryForList("SELECT image_id FROM store_render_item WHERE batch_id=? GROUP BY image_id ORDER BY MIN(id) DESC LIMIT ? OFFSET ?",
            Long.class, batchId, size, (Math.max(1, pageNum) - 1) * size);
        if (imageIds.isEmpty()) return new StoreDtos.PageView<>(List.of(), total);
        Map<Long, String> originalNames = new HashMap<>();
        imageMapper.selectByIds(imageIds).forEach(image -> originalNames.put(image.getId(), image.getOriginalName()));
        List<StoreRenderItem> items = itemMapper.selectList(Wrappers.<StoreRenderItem>lambdaQuery()
            .eq(StoreRenderItem::getBatchId, batchId).in(StoreRenderItem::getImageId, imageIds));
        items.stream().filter(item -> item.getResultOssKey() != null)
            .forEach(item -> item.setResultUrl(storeStorage.url(item.getResultOssKey(), "image/*")));
        Map<Long, List<StoreRenderItem>> grouped = new HashMap<>();
        items.forEach(item -> grouped.computeIfAbsent(item.getImageId(), ignored -> new ArrayList<>()).add(item));
        return new StoreDtos.PageView<>(imageIds.stream().map(id -> new StoreDtos.RenderGroupView(id, originalNames.get(id),
            grouped.getOrDefault(id, List.of()).stream().sorted(Comparator.comparingInt(item -> {
                Object order = templateConfig(item.getTemplateConfigJson()).get("sortOrder");
                return order instanceof Number number ? number.intValue() : Integer.MAX_VALUE;
            })).toList())).toList(), total);
    }
    public StoreDtos.PageView<StoreDtos.RenderLogView> renderLogs(String userName, int pageNum, int pageSize) {
        int page = Math.max(1, pageNum);
        int size = Math.max(1, Math.min(100, pageSize));
        StringBuilder where = new StringBuilder(" FROM store_render_item i LEFT JOIN img_asset a ON a.id=i.image_id LEFT JOIN sys_user u ON u.user_id=i.owner_id WHERE i.status='SUCCEEDED'");
        List<Object> args = new ArrayList<>();
        if (!LoginHelper.isAdmin()) { where.append(" AND i.owner_id=?"); args.add(owner()); }
        if (userName != null && !userName.isBlank()) { where.append(" AND u.user_name=?"); args.add(userName.trim()); }
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(size); pageArgs.add((page - 1) * size);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT i.id, i.batch_id, i.image_id, COALESCE(a.display_name,a.original_name,CAST(i.image_id AS CHAR)) image_name, COALESCE(u.user_name,CAST(i.owner_id AS CHAR)) user_name, COALESCE(i.completed_at,i.create_time) rendered_at, COALESCE(a.render_use_count,0) render_use_count" + where + " ORDER BY COALESCE(i.completed_at,i.create_time) DESC, i.id DESC LIMIT ? OFFSET ?", pageArgs.toArray());
        List<StoreDtos.RenderLogView> records = rows.stream().map(row -> new StoreDtos.RenderLogView(
            ((Number) row.get("id")).longValue(), ((Number) row.get("batch_id")).longValue(), ((Number) row.get("image_id")).longValue(),
            Objects.toString(row.get("image_name"), ""), Objects.toString(row.get("user_name"), ""), Objects.toString(row.get("rendered_at"), ""),
            ((Number) row.getOrDefault("render_use_count", 0)).intValue())).toList();
        return new StoreDtos.PageView<>(records, total == null ? 0 : total);
    }
    public List<StoreDtos.ArchiveFile> archiveFiles(long batchId, String nameMode) {
        requireBatch(batchId);
        if (!Set.of("ORIGINAL_NAME", "ENGLISH_KEYWORDS").contains(nameMode)) throw new ServiceException("图片命名方式无效");
        List<StoreRenderItem> items = itemMapper.selectList(Wrappers.<StoreRenderItem>lambdaQuery().eq(StoreRenderItem::getBatchId, batchId).in(StoreRenderItem::getStatus, "SUCCEEDED", "SKIPPED"));
        if (items.isEmpty()) throw new ServiceException("该批次暂无可下载图片");
        List<Long> ids = items.stream().map(StoreRenderItem::getImageId).distinct().toList();
        Map<Long, ImageAsset> images = new HashMap<>();
        for (int start = 0; start < ids.size(); start += 500) {
            imageMapper.selectByIds(ids.subList(start, Math.min(start + 500, ids.size())))
                .forEach(image -> images.put(image.getId(), image));
        }
        Map<Long, InfringementScanItem> scans = new HashMap<>();
        if ("ENGLISH_KEYWORDS".equals(nameMode)) {
            List<Long> scanIds = images.values().stream().map(ImageAsset::getInfringementScanItemId).filter(Objects::nonNull).distinct().toList();
            for (int start = 0; start < scanIds.size(); start += 500)
                scans.putAll(infringementService.currentResults(scanIds.subList(start, Math.min(start + 500, scanIds.size()))));
        }
        Map<Long, List<StoreRenderItem>> grouped = new TreeMap<>();
        items.forEach(item -> grouped.computeIfAbsent(item.getImageId(), ignored -> new ArrayList<>()).add(item));
        Set<String> used = new HashSet<>();
        List<StoreDtos.ArchiveFile> files = new ArrayList<>();
        for (var group : grouped.entrySet()) {
            ImageAsset image = images.get(group.getKey());
            if (image == null || image.getStorageKey() == null) throw new ServiceException("原图不存在");
            String name = "ORIGINAL_NAME".equals(nameMode) ? image.getOriginalName()
                : InfringementService.aiListingKeywords(scans.get(image.getInfringementScanItemId()));
            if (name == null || name.isBlank()) throw new ServiceException("原图 " + image.getId() + " 缺少可用的命名信息");
            String base = ("ORIGINAL_NAME".equals(nameMode) ? name.replaceFirst("\\.[^.]*$", "") : name)
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
            if (base.isBlank() || base.equals(".") || base.equals("..")) throw new ServiceException("原图 " + image.getId() + " 的文件名无效");
            String originalExtension = switch (Objects.toString(image.getContentType(), "")) {
                case "image/png" -> ".png";
                case "image/webp" -> ".webp";
                case "image/jpeg" -> ".jpg";
                default -> image.getOriginalName() == null ? ".jpg" : image.getOriginalName().replaceFirst("^.*(\\.[^.]+)$", "$1").toLowerCase(Locale.ROOT);
            };
            if (!Set.of(".jpg", ".jpeg", ".png", ".webp").contains(originalExtension)) originalExtension = ".jpg";
            files.add(new StoreDtos.ArchiveFile(image.getStorageKey(), uniqueArchiveName(base, originalExtension, 0, used), true));
            group.getValue().sort(Comparator.comparingInt((StoreRenderItem item) -> {
                Object order = templateConfig(item.getTemplateConfigJson()).get("sortOrder");
                return order instanceof Number number ? number.intValue() : Integer.MAX_VALUE;
            }).thenComparing(StoreRenderItem::getTemplateId).thenComparing(StoreRenderItem::getId));
            int index = 1;
            for (StoreRenderItem item : group.getValue()) {
                if (item.getResultOssKey() == null) throw new ServiceException("渲染文件不存在");
                String extension = "png".equalsIgnoreCase(item.getOutputFormat()) ? ".png" : ".jpg";
                files.add(new StoreDtos.ArchiveFile(item.getResultOssKey(), uniqueArchiveName(base, extension, index++, used), false));
            }
        }
        return files;
    }
    private static String uniqueArchiveName(String base, String extension, int index, Set<String> used) {
        String name;
        do { name = base + (index == 0 ? "" : "(" + index + ")") + extension; index++; } while (!used.add(name.toLowerCase(Locale.ROOT)));
        return name;
    }
    public ImageStorage.LocalFile archiveFile(StoreDtos.ArchiveFile file) {
        return file.original() ? storage.open(file.ossKey()) : storeStorage.open(file.ossKey());
    }

    @Transactional
    public StoreDtos.ClaimView claim(StoreDtos.ClaimRequest request) {
        int limit = request.limit() == null ? DEFAULT_CLAIM_LIMIT : Math.max(1, Math.min(50, request.limit()));
        List<StoreDtos.ClaimedItem> result = new ArrayList<>(); LocalDateTime until = LocalDateTime.now().plusSeconds(LEASE_SECONDS);
        for (StoreRenderItem item : itemMapper.selectClaimable(limit)) {
            String token = IdUtil.fastSimpleUUID(); if (itemMapper.claim(item.getId(), request.workerId(), token, until) != 1) continue;
            item.setLeaseToken(token); item.setWorkerId(request.workerId());
            StoreTemplate template = templateMapper.selectById(item.getTemplateId());
            ImageAsset image = imageMapper.selectById(item.getImageId());
            String imageUrl = image == null ? null : "/store/internal/images/" + image.getId() + "/access";
            String templateUrl = template == null ? null : "/store/internal/templates/" + template.getId() + "/access";
            String maskUrl = item.getMaskOssKey() == null ? null : "/store/internal/masks/" + item.getId() + "/access";
            result.add(new StoreDtos.ClaimedItem(item.getId(), item.getBatchId(), item.getImageId(), item.getTemplateId(), imageUrl, templateUrl, maskUrl, item.getCalibrationConfigJson(), item.getTemplateConfigJson(), item.getOutputFormat(), item.getQuality(), token));
        }
        return new StoreDtos.ClaimView(result, result.isEmpty() ? 1000 : 0);
    }

    public void renew(long itemId, StoreDtos.RenewRequest request) {
        if (itemMapper.renew(itemId, request.workerId(), request.leaseToken(), LocalDateTime.now().plusSeconds(LEASE_SECONDS)) != 1)
            throw new ServiceException("渲染租约已失效");
    }

    @Transactional
    public StoreDtos.UploadView uploadResult(long itemId, String workerId, String leaseToken, MultipartFile file) {
        StoreRenderItem item=itemMapper.selectById(itemId);
        if(item==null || !"RUNNING".equals(item.getStatus()) || !Objects.equals(item.getWorkerId(), workerId) || !Objects.equals(item.getLeaseToken(), leaseToken) || (item.getLeaseUntil()!=null && item.getLeaseUntil().isBefore(LocalDateTime.now()))) throw new ServiceException("渲染租约已失效");
        return storeStorage.save(item.getOwnerId(), "results", file);
    }
    public String imageAccess(long imageId) {
        ImageAsset image=imageMapper.selectById(imageId); if(image==null || !ImageStatus.APPROVED.name().equals(image.getStatus())) throw new ServiceException("图片不存在");
        return storage.accessUrl(image.getStorageKey(), image.getContentType(), false).orElse("/store/internal/images/" + imageId + "/file");
    }
    public String templateAccess(long templateId) {
        StoreTemplate template=templateMapper.selectById(templateId); if(template==null) throw new ServiceException("模板不存在");
        return storage.accessUrl(template.getOssKey(), "image/*", false).orElse("/store/internal/templates/" + templateId + "/file");
    }
    public ImageStorage.LocalFile imageFile(long imageId) {
        ImageAsset image=imageMapper.selectById(imageId); if(image==null || !ImageStatus.APPROVED.name().equals(image.getStatus())) throw new ServiceException("图片不存在");
        return storage.open(image.getStorageKey());
    }
    public ImageStorage.LocalFile templateFile(long templateId) {
        StoreTemplate template=templateMapper.selectById(templateId); if(template==null) throw new ServiceException("模板不存在");
        return storeStorage.open(template.getOssKey());
    }
    public String maskAccess(long itemId) {
        StoreRenderItem item = itemMapper.selectById(itemId);
        if (item == null || item.getMaskOssKey() == null) throw new ServiceException("蒙版不存在");
        return storage.accessUrl(item.getMaskOssKey(), "image/png", false).orElse("/store/internal/masks/" + itemId + "/file");
    }
    public ImageStorage.LocalFile maskFile(long itemId) {
        StoreRenderItem item = itemMapper.selectById(itemId);
        if (item == null || item.getMaskOssKey() == null) throw new ServiceException("蒙版不存在");
        return storeStorage.open(item.getMaskOssKey());
    }

    @Transactional
    public void complete(long itemId, StoreDtos.CompleteRequest request) {
        StoreRenderItem item = itemMapper.selectById(itemId);
        if (item == null) throw new ServiceException("渲染任务不存在");
        jdbcTemplate.queryForObject("SELECT id FROM img_asset WHERE id=? FOR UPDATE", Long.class, item.getImageId());
        Long prior = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM store_render_item WHERE image_id=? AND template_group_id=? AND status='SUCCEEDED' FOR UPDATE", Long.class, item.getImageId(), item.getTemplateGroupId());
        if (itemMapper.complete(itemId, request.workerId(), request.leaseToken(), request.result().ossKey(), request.result().sha256()) != 1)
            throw new ServiceException("渲染租约已失效");
        item = itemMapper.selectById(itemId);
        item.setResultUrl(storeStorage.url(request.result().ossKey(), "image/*"));
        itemMapper.updateById(item);
        if (prior != null && prior == 0) imageMapper.update(null, Wrappers.<ImageAsset>lambdaUpdate()
            .eq(ImageAsset::getId, item.getImageId()).setSql("render_use_count = COALESCE(render_use_count, 0) + 1"));
        batchMapper.completeItem(item.getBatchId());
    }
    public ImageStorage.LocalFile ownedFile(String key) {
        if (key == null || !key.matches("store/(templates|results|listing)/" + owner() + "/[0-9a-f-]+\\.(jpg|jpeg|png|webp|xlsx)")) throw new ServiceException("无权访问文件");
        return storeStorage.open(key);
    }
    @Transactional
    public void fail(long itemId, StoreDtos.FailRequest request) { if (itemMapper.fail(itemId, request.workerId(), request.leaseToken(), request.errorMessage()) != 1) throw new ServiceException("渲染租约已失效"); batchMapper.failItem(itemMapper.selectById(itemId).getBatchId()); }
    private StoreDtos.TemplateGroupView groupView(StoreTemplateGroup g) { List<StoreDtos.TemplateView> ts=templateMapper.selectList(Wrappers.<StoreTemplate>lambdaQuery().eq(StoreTemplate::getGroupId,g.getId()).eq(StoreTemplate::getStatus,"ACTIVE")).stream().sorted(Comparator.comparingInt(this::templateOrder).thenComparing(StoreTemplate::getId)).map(t->new StoreDtos.TemplateView(t.getId(),t.getName(),t.getOssKey(),storeStorage.url(t.getOssKey(),"image/*"),t.getTemplateConfigJson(),t.getWidth(),t.getHeight())).toList(); return new StoreDtos.TemplateGroupView(g.getId(),g.getName(),g.getCategoryId(),g.getCalibrationConfigJson(),g.getMaskOssKey(),g.getStatus(),ts); }
    private StoreDtos.RenderBatchView batchView(StoreRenderBatch b) { return new StoreDtos.RenderBatchView(b.getId(),b.getStatus(),b.getCategoryId(),b.getTemplateGroupId(),b.getShopId(),b.getListingTemplateId(),b.getSelectedCount(),b.getCompletedCount(),b.getFailedCount(),b.getSkippedCount()); }
    private StoreTemplateGroup requireGroup(long id){ StoreTemplateGroup g=groupMapper.selectById(id); if(g==null||"DELETED".equals(g.getStatus())) throw new ServiceException("模板组不存在"); checkOwner(g.getOwnerId()); return g; }
    private StoreShop requireShop(long id){ StoreShop shop=shopMapper.selectById(id); if(shop==null||!"ACTIVE".equals(shop.getStatus())) throw new ServiceException("店铺不存在"); checkOwner(shop.getOwnerId()); return shop; }
    private StoreListingTemplate requireListingTemplate(long id){ StoreListingTemplate template=listingTemplateMapper.selectById(id); if(template==null||!"ACTIVE".equals(template.getStatus())) throw new ServiceException("上架表格不存在"); checkOwner(template.getOwnerId()); return template; }
    private StoreRenderBatch requireBatch(long id){ StoreRenderBatch b=batchMapper.selectById(id); if(b==null) throw new ServiceException("渲染批次不存在"); checkOwner(b.getOwnerId()); return b; }
    private <T> com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<T> scope(Class<T> type){ return Wrappers.<T>query().eq("owner_id", owner()); }
    private long owner(){ String id=LoginHelper.getUserIdStr(); if(id==null || id.isBlank()) throw new ServiceException("登录已失效"); try { return Long.parseLong(id); } catch (NumberFormatException e) { throw new ServiceException("登录用户无效"); } }
    private void checkOwner(Long id){ if(!Objects.equals(id,owner())) throw new ServiceException("无权访问"); }
    private static long nextId(){ return Long.parseLong(IdGeneratorUtil.nextId()); }
    private static String sha256(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
