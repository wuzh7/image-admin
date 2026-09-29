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

    @Select("""
        SELECT i.id, i.image_id, a.storage_key
        FROM infringement_scan_item i
        JOIN img_asset a ON a.id = i.image_id AND a.infringement_scan_item_id = i.id
        WHERE i.status = 'SUCCESS' AND i.external_completed_at IS NULL
          AND a.status = 'APPROVED' AND a.del_flag = '0' AND a.storage_key IS NOT NULL
          AND (i.external_claim_token IS NULL OR i.external_claimed_at < #{cutoff})
        ORDER BY i.id LIMIT 200 FOR UPDATE SKIP LOCKED
        """)
    List<org.dromara.image.domain.ExternalInfringementDtos.ClaimRow> selectExternalUnclaimed(@Param("cutoff") java.time.LocalDateTime cutoff);

    @Update("""
        <script>
        UPDATE infringement_scan_item SET external_claim_token = #{token}, external_claimed_at = #{claimedAt}
        WHERE id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        </script>
        """)
    int claimExternal(@Param("ids") List<Long> ids, @Param("token") String token,
                      @Param("claimedAt") java.time.LocalDateTime claimedAt);

    @Update("""
        UPDATE infringement_scan_item i
        JOIN img_asset a ON a.id = i.image_id AND a.infringement_scan_item_id = i.id
        SET i.risk_level = #{riskLevel}, i.risk_reason = #{riskReason},
            i.case_numbers_json = #{caseNumbersJson}, i.verification_urls_json = #{verificationUrlsJson},
            i.external_completed_at = NOW(), i.update_time = NOW()
        WHERE i.image_id = #{imageId} AND i.external_claim_token = #{token}
          AND i.status = 'SUCCESS' AND i.external_completed_at IS NULL
          AND a.status = 'APPROVED' AND a.del_flag = '0'
        """)
    int completeExternal(@Param("imageId") Long imageId, @Param("token") String token,
                         @Param("riskLevel") String riskLevel, @Param("riskReason") String riskReason,
                         @Param("caseNumbersJson") String caseNumbersJson,
                         @Param("verificationUrlsJson") String verificationUrlsJson);

    @Select("""
        SELECT i.* FROM infringement_scan_item i
        JOIN img_asset a ON a.id = i.image_id AND a.infringement_scan_item_id = i.id
        WHERE i.image_id = #{imageId} AND a.status = 'APPROVED' AND a.del_flag = '0'
        """)
    InfringementScanItem selectCurrentExternal(@Param("imageId") long imageId);

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
            WHERE i.status = 'PENDING' AND i.execution_mode = 'RELAY' AND i.call_type = 'IMAGE_ANALYSIS' AND a.del_flag = '0' AND a.status = 'APPROVED'
        ) ranked
        ORDER BY ranked.task_rank, ranked.task_create_time, ranked.task_id, ranked.id
        LIMIT #{limit}
        """)
    List<InfringementScanItem> selectPendingRelay(@Param("limit") int limit);

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
