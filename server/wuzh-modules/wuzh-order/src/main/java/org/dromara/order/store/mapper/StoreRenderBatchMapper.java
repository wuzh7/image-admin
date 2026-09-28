package org.dromara.order.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;
import org.dromara.order.store.domain.StoreRenderBatch;
import java.util.List;

@Mapper
public interface StoreRenderBatchMapper extends BaseMapper<StoreRenderBatch> {
    @Select("SELECT b.* FROM store_render_batch b LEFT JOIN store_listing_export e ON e.batch_id=b.id WHERE b.status='COMPLETED' AND b.shop_id IS NOT NULL AND b.listing_template_id IS NOT NULL AND e.id IS NULL ORDER BY b.id LIMIT 20")
    List<StoreRenderBatch> selectListingReady();
    @Update("UPDATE store_render_batch SET status=CASE WHEN status IN ('STOPPING','STOPPED') THEN status WHEN completed_count+skipped_count+failed_count+1>=selected_count THEN IF(failed_count>0,'PARTIAL_FAILURE','COMPLETED') ELSE 'RUNNING' END, completed_count=completed_count+1, update_time=NOW() WHERE id=#{id}")
    int completeItem(@Param("id") long id);

    @Update("UPDATE store_render_batch SET status=CASE WHEN status IN ('STOPPING','STOPPED') THEN status WHEN completed_count+skipped_count+failed_count+1>=selected_count THEN 'PARTIAL_FAILURE' ELSE 'RUNNING' END, failed_count=failed_count+1, update_time=NOW() WHERE id=#{id}")
    int failItem(@Param("id") long id);

    @Update("UPDATE store_render_batch b SET b.status='STOPPED', b.update_time=NOW() WHERE b.id=#{id} AND b.status='STOPPING' AND NOT EXISTS (SELECT 1 FROM store_render_item i WHERE i.batch_id=b.id AND i.status='RUNNING')")
    int stopIfIdle(@Param("id") long id);
}
