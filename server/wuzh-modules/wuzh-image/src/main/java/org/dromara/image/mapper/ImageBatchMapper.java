package org.dromara.image.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.dromara.image.domain.ImageBatch;

public interface ImageBatchMapper extends BaseMapper<ImageBatch> {

    @org.apache.ibatis.annotations.Select("SELECT COUNT(*) FROM img_batch WHERE status IN ('QUEUED', 'CHECKING', 'CONFIRMING')")
    long countResetBlockingBatches();

    @Delete("DELETE FROM img_batch")
    int deleteAllForReset();

    @org.apache.ibatis.annotations.Select("SELECT * FROM img_batch WHERE id = #{id} FOR UPDATE")
    ImageBatch lockBatch(@Param("id") long id);

    @Update("""
        UPDATE img_batch
        SET status = 'CONFIRMING', update_time = SYSDATE()
        WHERE id = #{id} AND status = 'WAIT_REVIEW'
        """)
    int claimForConfirmation(@Param("id") long id);

    @Update("""
        UPDATE img_batch
        SET status = 'QUEUED', error_message = NULL, update_time = SYSDATE()
        WHERE id = #{id} AND status = 'UPLOADING'
        """)
    int queueForDetection(@Param("id") long id);
}
