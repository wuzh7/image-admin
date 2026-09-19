package org.dromara.image.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.image.domain.InfringementCallLog;
import org.dromara.image.domain.InfringementDtos;

@Mapper
public interface InfringementCallLogMapper extends BaseMapper<InfringementCallLog> {

    @Delete("DELETE FROM infringement_call_log")
    int deleteAllForReset();

    @Select("""
        <script>
        SELECT l.id, l.task_id, l.scan_item_id, l.batch_id, l.image_id, a.display_name image_name,
          l.request_id, l.provider_job_id, l.provider_request_index, l.execution_mode, l.call_type,
          l.model, l.status, l.risk_level, l.raw_ai_response, l.usage_metadata,
          l.prompt_token_count, l.cached_content_token_count, l.candidates_token_count,
          l.thoughts_token_count, l.tool_use_prompt_token_count, l.total_token_count,
          l.search_used, l.search_query_count, l.duration_ms, l.error_code, l.error_message,
          l.started_at, l.finished_at
        FROM infringement_call_log l
        LEFT JOIN img_asset a ON a.id = l.image_id
        WHERE l.task_id = #{taskId}
          <if test="imageId != null">AND l.image_id = #{imageId}</if>
          <if test="status != null and status != ''">AND l.status = #{status}</if>
          <if test="executionMode != null and executionMode != ''">AND l.execution_mode = #{executionMode}</if>
        ORDER BY l.id DESC
        </script>
        """)
    Page<InfringementDtos.CallLogRow> selectPageByTask(Page<InfringementDtos.CallLogRow> page,
        @Param("taskId") long taskId, @Param("imageId") Long imageId,
        @Param("status") String status, @Param("executionMode") String executionMode);

    @Select("SELECT id, raw_provider_response FROM infringement_call_log WHERE task_id = #{taskId} AND id = #{callId}")
    InfringementDtos.CallResponseView selectRawResponse(@Param("taskId") long taskId, @Param("callId") long callId);
}
