package org.dromara.image.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.image.domain.StoreProbe;
import org.dromara.image.domain.StoreProbeDtos;
import org.dromara.image.mapper.StoreProbeMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StoreProbeService {
    private final StoreProbeMapper mapper;

    public boolean contains(String storeId) {
        String value = StringUtils.trimToNull(storeId);
        return value != null && mapper.selectCount(new LambdaQueryWrapper<StoreProbe>()
            .eq(StoreProbe::getStoreId, value)) > 0;
    }

    public Map<String, StoreInfo> read() {
        Map<String, StoreInfo> stores = new LinkedHashMap<>();
        mapper.selectList(new LambdaQueryWrapper<StoreProbe>().orderByAsc(StoreProbe::getId)).forEach(row -> {
            String storeId = StringUtils.trimToNull(row.getStoreId());
            if (storeId != null) stores.put(storeId, new StoreInfo(row.getStoreName(), row.getRemarks()));
        });
        return stores;
    }

    public PageResult<StoreProbeDtos.View> list(String storeId, String storeName, PageQuery pageQuery) {
        LambdaQueryWrapper<StoreProbe> query = new LambdaQueryWrapper<StoreProbe>()
            .like(StringUtils.isNotBlank(storeId), StoreProbe::getStoreId, StringUtils.trim(storeId))
            .like(StringUtils.isNotBlank(storeName), StoreProbe::getStoreName, StringUtils.trim(storeName))
            .orderByDesc(StoreProbe::getId);
        Page<StoreProbe> page = mapper.selectPage(pageQuery.build(), query);
        return PageResult.build(page.getRecords().stream().map(this::view).toList(), page.getTotal());
    }

    public StoreProbeDtos.View get(long id) {
        return view(require(id));
    }

    public StoreProbeDtos.View save(StoreProbeDtos.SaveRequest request, boolean edit) {
        String storeId = StringUtils.trimToNull(request.storeId());
        if (storeId == null) throw new ServiceException("店铺ID不能为空");
        boolean exists = mapper.selectCount(new LambdaQueryWrapper<StoreProbe>()
            .eq(StoreProbe::getStoreId, storeId).ne(edit, StoreProbe::getId, request.id())) > 0;
        if (exists) throw new ServiceException("店铺ID已存在");
        StoreProbe row = edit ? require(request.id()) : new StoreProbe();
        if (!edit) row.setId(IdWorker.getId());
        row.setStoreId(storeId);
        row.setStoreName(StringUtils.trimToEmpty(request.storeName()));
        row.setRemarks(StringUtils.trimToEmpty(request.remarks()));
        if ((edit ? mapper.updateById(row) : mapper.insert(row)) != 1) throw new ServiceException("店铺数据保存失败");
        return view(row);
    }

    public void remove(long id) {
        if (mapper.deleteById(id) != 1) throw new ServiceException("店铺数据不存在");
    }

    private StoreProbe require(Long id) {
        StoreProbe row = id == null ? null : mapper.selectById(id);
        if (row == null) throw new ServiceException("店铺数据不存在");
        return row;
    }

    private StoreProbeDtos.View view(StoreProbe row) {
        return new StoreProbeDtos.View(row.getId(), row.getStoreId(), row.getStoreName(), row.getRemarks(), row.getCreateTime(), row.getUpdateTime());
    }

    public record StoreInfo(String name, String remarks) {
    }
}
