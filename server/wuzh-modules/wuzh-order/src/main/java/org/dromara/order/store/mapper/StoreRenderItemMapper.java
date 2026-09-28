package org.dromara.order.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.order.store.domain.StoreRenderItem;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface StoreRenderItemMapper extends BaseMapper<StoreRenderItem> {
    @Select("SELECT i.* FROM store_render_item i JOIN store_render_batch b ON b.id=i.batch_id WHERE b.status IN ('CREATED','RUNNING') AND (i.status = 'PENDING' OR (i.status = 'RUNNING' AND i.lease_until < NOW())) ORDER BY i.id LIMIT #{limit} FOR UPDATE SKIP LOCKED")
    List<StoreRenderItem> selectClaimable(@Param("limit") int limit);

    @Update("UPDATE store_render_item SET status='RUNNING', worker_id=#{workerId}, lease_token=#{token}, lease_until=#{leaseUntil}, started_at=COALESCE(started_at,NOW()), update_time=NOW() WHERE id=#{id} AND EXISTS (SELECT 1 FROM store_render_batch b WHERE b.id=store_render_item.batch_id AND b.status IN ('CREATED','RUNNING')) AND (status='PENDING' OR (status='RUNNING' AND lease_until < NOW()))")
    int claim(@Param("id") long id, @Param("workerId") String workerId, @Param("token") String token,
              @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("UPDATE store_render_item SET status='CANCELLED', lease_until=NULL, lease_token=NULL, worker_id=NULL, completed_at=NOW(), update_time=NOW() WHERE batch_id=#{batchId} AND status='RUNNING' AND lease_until<NOW()")
    int cancelExpired(@Param("batchId") long batchId);

    @Update("UPDATE store_render_item SET lease_until=#{leaseUntil}, update_time=NOW() WHERE id=#{id} AND status='RUNNING' AND worker_id=#{workerId} AND lease_token=#{token}")
    int renew(@Param("id") long id, @Param("workerId") String workerId, @Param("token") String token,
              @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("UPDATE store_render_item SET status='SUCCEEDED', result_oss_key=#{ossKey}, result_sha256=#{sha256}, completed_at=NOW(), lease_until=NULL, lease_token=NULL, worker_id=NULL, error_message=NULL, update_time=NOW() WHERE id=#{id} AND status='RUNNING' AND lease_token=#{token} AND worker_id=#{workerId}")
    int complete(@Param("id") long id, @Param("workerId") String workerId, @Param("token") String token,
                 @Param("ossKey") String ossKey, @Param("sha256") String sha256);

    @Update("UPDATE store_render_item SET status='FAILED', error_message=#{message}, completed_at=NOW(), lease_until=NULL, lease_token=NULL, worker_id=NULL, update_time=NOW() WHERE id=#{id} AND status='RUNNING' AND lease_token=#{token} AND worker_id=#{workerId}")
    int fail(@Param("id") long id, @Param("workerId") String workerId, @Param("token") String token,
             @Param("message") String message);
}
