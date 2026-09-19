package org.dromara.image.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.image.domain.ImageAsset;
import org.dromara.image.domain.InfringementDtos;
import org.dromara.image.domain.InfringementScanItem;

import java.util.Collection;
import java.util.List;

@Mapper
public interface InfringementScanItemMapper extends BaseMapper<InfringementScanItem> {

    @Select("SELECT COUNT(*) FROM infringement_scan_item WHERE status = 'PROCESSING'")
    long countProcessingForReset();

    @Delete("DELETE FROM infringement_scan_item")
    int deleteAllForReset();

    @Select("""
        SELECT ranked.* FROM (
            SELECT i.*, t.source_batch_id batch_id, 'RELAY' infringement_mode,
                   ROW_NUMBER() OVER (PARTITION BY i.task_id ORDER BY i.id) task_rank,
                   t.create_time task_create_time
            FROM infringement_scan_item i
            JOIN img_asset a ON a.id = i.image_id AND a.infringement_scan_item_id = i.id
            JOIN infringement_scan_task t ON t.id = i.task_id
            WHERE i.status = 'PENDING' AND i.execution_mode = 'RELAY' AND a.del_flag = '0' AND a.status = 'APPROVED'
        ) ranked
        ORDER BY ranked.task_rank, ranked.task_create_time, ranked.task_id, ranked.id
        LIMIT #{limit}
        """)
    List<InfringementScanItem> selectPendingRelay(@Param("limit") int limit);

    @Select("""
        SELECT i.*, t.source_batch_id batch_id, 'OFFICIAL_STANDARD' infringement_mode
        FROM infringement_scan_item i
        JOIN img_asset a ON a.id = i.image_id AND a.infringement_scan_item_id = i.id
        JOIN infringement_scan_task t ON t.id = i.task_id
        WHERE i.status = 'PENDING'
          AND (i.execution_mode = 'OFFICIAL_STANDARD' OR (i.execution_mode = 'OFFICIAL_BATCH' AND i.call_type = 'CASE_LOOKUP'))
          AND a.del_flag = '0' AND a.status = 'APPROVED'
        ORDER BY t.create_time, t.id, i.id
        LIMIT #{limit}
        """)
    List<InfringementScanItem> selectPendingOfficial(@Param("limit") int limit);

    @Select("SELECT id FROM infringement_scan_task ORDER BY id LIMIT 1 FOR UPDATE")
    Long lockBatchQueue();

    @Select("""
        SELECT COUNT(*) FROM (
            SELECT COALESCE(provider_job_id, last_request_id, CONCAT('item-', id)) group_key
            FROM infringement_scan_item
            WHERE status = 'PROCESSING' AND execution_mode = 'OFFICIAL_BATCH' AND call_type = 'RISK_SCAN'
            GROUP BY group_key
        ) active_jobs
        """)
    int countActiveBatchJobs();

    @Select("""
        SELECT i.task_id
        FROM infringement_scan_item i
        JOIN img_asset a ON a.id = i.image_id AND a.infringement_scan_item_id = i.id
        JOIN infringement_scan_task t ON t.id = i.task_id
        WHERE i.status = 'PENDING' AND i.execution_mode = 'OFFICIAL_BATCH' AND i.call_type = 'RISK_SCAN' AND a.del_flag = '0' AND a.status = 'APPROVED'
        ORDER BY t.create_time, t.id, i.id
        LIMIT 1
        FOR UPDATE
        """)
    Long selectOldestPendingBatchTaskForUpdate();

    @Select("""
        SELECT i.*, t.source_batch_id batch_id
        FROM infringement_scan_item i
        JOIN img_asset a ON a.id = i.image_id AND a.infringement_scan_item_id = i.id
        JOIN infringement_scan_task t ON t.id = i.task_id
        WHERE i.status = 'PENDING' AND i.execution_mode = 'OFFICIAL_BATCH'
          AND i.call_type = 'RISK_SCAN'
          AND i.task_id = #{taskId} AND a.del_flag = '0' AND a.status = 'APPROVED'
        ORDER BY i.id
        LIMIT #{limit}
        FOR UPDATE
        """)
    List<InfringementScanItem> selectPendingBatchForUpdate(@Param("taskId") long taskId,
                                                            @Param("limit") int limit);

    @Update("""
        <script>
        UPDATE infringement_scan_item
        SET status = 'PROCESSING', attempt_count = attempt_count + 1,
            last_request_id = #{requestId}, started_at = NOW(), finished_at = NULL,
            provider_job_id = NULL, provider_request_index = NULL,
            error_code = NULL, error_message = NULL
        WHERE status = 'PENDING' AND id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        </script>
        """)
    int claimPendingBatch(@Param("ids") Collection<Long> ids, @Param("requestId") String requestId);

    @Update("""
        UPDATE infringement_scan_item
        SET provider_request_index = #{index}
        WHERE id = #{id} AND status = 'PROCESSING' AND last_request_id = #{requestId}
        """)
    int setBatchRequestIndex(@Param("id") long id, @Param("requestId") String requestId,
                             @Param("index") int index);

    @Update("""
        UPDATE infringement_scan_item
        SET provider_job_id = #{jobId}
        WHERE status = 'PROCESSING' AND execution_mode = 'OFFICIAL_BATCH'
          AND last_request_id = #{requestId}
        """)
    int bindBatchJob(@Param("requestId") String requestId, @Param("jobId") String jobId);

    @Select("""
        SELECT DISTINCT i.provider_job_id
        FROM infringement_scan_item i
        JOIN img_asset a ON a.id = i.image_id AND a.infringement_scan_item_id = i.id
        WHERE i.status = 'PROCESSING'
          AND i.call_type = 'RISK_SCAN'
          AND i.provider_job_id IS NOT NULL
          AND a.del_flag = '0' AND a.status = 'APPROVED'
        ORDER BY i.provider_job_id
        LIMIT #{limit}
        """)
    List<String> selectProcessingBatchJobs(@Param("limit") int limit);

    @Select("""
        SELECT i.*, t.source_batch_id batch_id
        FROM infringement_scan_item i
        JOIN img_asset a ON a.id = i.image_id AND a.infringement_scan_item_id = i.id
        JOIN infringement_scan_task t ON t.id = i.task_id
        WHERE i.status = 'PROCESSING'
          AND i.call_type = 'RISK_SCAN'
          AND i.provider_job_id = #{jobId}
          AND a.del_flag = '0' AND a.status = 'APPROVED'
        ORDER BY i.provider_request_index
        """)
    List<InfringementScanItem> selectProcessingBatchItems(@Param("jobId") String jobId);

    @Select("""
        <script>
        SELECT task_id,
               COUNT(*) total_count,
               SUM(status = 'SUCCESS') success_count,
               SUM(status = 'PROCESSING') processing_count,
               SUM(status = 'SUCCESS' AND risk_level = 'HIGH') high_risk_count,
               SUM(status = 'SUCCESS' AND risk_level = 'LOW') low_risk_count,
               SUM(status = 'SUCCESS' AND risk_level = 'NEEDS_REVIEW') needs_review_count,
               SUM(status = 'FAILED') failed_count
        FROM infringement_scan_item
        WHERE task_id IN
        <foreach collection="taskIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        GROUP BY task_id
        </script>
        """)
    List<InfringementDtos.TaskStats> selectTaskStats(@Param("taskIds") Collection<Long> taskIds);

    @Select("""
        <script>
        SELECT i.id, i.image_id, a.display_name image_name, a.status image_status,
               i.status, i.risk_level, i.risk_reason, i.case_numbers_json, i.image_description, i.raw_ai_response, i.model,
               i.prompt_version, i.execution_mode, i.search_used, i.search_query_count,
               i.attempt_count, i.error_message, i.finished_at
        FROM infringement_scan_item i
        JOIN img_asset a ON a.id = i.image_id
        WHERE i.task_id = #{taskId}
          <if test="status != null and status != ''">AND i.status = #{status}</if>
          <if test="riskLevel != null and riskLevel != ''">AND i.risk_level = #{riskLevel}</if>
        ORDER BY i.id
        </script>
        """)
    Page<InfringementDtos.ItemRow> selectItemPage(Page<InfringementDtos.ItemRow> page,
        @Param("taskId") long taskId, @Param("status") String status, @Param("riskLevel") String riskLevel);

    @Select("""
        SELECT a.*
        FROM img_asset a
        JOIN infringement_scan_item i ON i.id = a.infringement_scan_item_id
        WHERE i.task_id = #{taskId}
          AND a.create_by = #{ownerId}
          AND a.status = 'APPROVED'
          AND a.del_flag = '0' AND a.status = 'APPROVED'
          AND i.status = 'FAILED'
        ORDER BY a.id
        """)
    List<ImageAsset> selectCurrentFailedImagesByTask(@Param("taskId") long taskId, @Param("ownerId") long ownerId);
}
