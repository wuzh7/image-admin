package org.dromara.order.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;
import org.dromara.order.store.domain.StoreListingExport;

@Mapper
public interface StoreListingExportMapper extends BaseMapper<StoreListingExport> {
    @Update("UPDATE store_listing_export e JOIN store_render_batch b ON b.id=e.batch_id SET e.status='PENDING', e.update_time=NOW() WHERE e.status='WAITING' AND b.status='COMPLETED'")
    int activateReady();
}
