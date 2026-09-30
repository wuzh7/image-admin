package org.dromara.image.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.dromara.image.domain.ImageAsset;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface ImageAssetMapper extends BaseMapper<ImageAsset> {

    @Select("""
        SELECT a.id, a.id image_id, a.storage_key
        FROM img_asset a
        WHERE a.status = 'APPROVED' AND a.del_flag = '0' AND a.storage_key IS NOT NULL
          AND a.external_completed_at IS NULL
          AND (a.external_claim_token IS NULL OR a.external_claimed_at < #{cutoff})
        ORDER BY a.id LIMIT 200 FOR UPDATE SKIP LOCKED
        """)
    List<org.dromara.image.domain.ExternalInfringementDtos.ClaimRow> selectExternalUnclaimed(@Param("cutoff") LocalDateTime cutoff);

    @Update("""
        <script>
        UPDATE img_asset SET external_claim_token = #{token}, external_claimed_at = #{claimedAt}
        WHERE id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
          AND status = 'APPROVED' AND del_flag = '0' AND external_completed_at IS NULL
        </script>
        """)
    int claimExternal(@Param("ids") List<Long> ids, @Param("token") String token,
                      @Param("claimedAt") LocalDateTime claimedAt);

    @Update("""
        UPDATE img_asset
        SET risk_level = #{riskLevel}, risk_reason = #{riskReason},
            case_numbers_json = #{caseNumbersJson}, verification_urls_json = #{verificationUrlsJson},
            external_completed_at = NOW(), update_time = NOW()
        WHERE id = #{imageId} AND external_claim_token = #{token}
          AND status = 'APPROVED' AND del_flag = '0' AND external_completed_at IS NULL
        """)
    int completeExternal(@Param("imageId") Long imageId, @Param("token") String token,
                         @Param("riskLevel") String riskLevel, @Param("riskReason") String riskReason,
                         @Param("caseNumbersJson") String caseNumbersJson,
                         @Param("verificationUrlsJson") String verificationUrlsJson);

    @Select("SELECT id FROM img_library_mutex WHERE id = 1 FOR UPDATE")
    Long lockLibrary();

    @Select("SELECT * FROM img_asset WHERE id = #{id} AND del_flag = '0' FOR UPDATE")
    ImageAsset lockAsset(@Param("id") long id);

    @Select("SELECT * FROM img_asset")
    List<ImageAsset> selectAllForReset();

    @Select("SELECT COUNT(*) FROM img_asset WHERE status IN ('UPLOADING', 'CHECKING', 'APPROVING')")
    long countResetBlockingAssets();

    @Delete("DELETE FROM img_asset")
    int deleteAllForReset();

    @Select("SELECT * FROM img_asset WHERE cleanup_key IS NOT NULL OR cleanup_thumbnail_key IS NOT NULL ORDER BY id LIMIT 100")
    List<ImageAsset> selectSourceCleanup();

    @Update("UPDATE img_asset SET cleanup_key = NULL, cleanup_thumbnail_key = NULL WHERE id = #{id}")
    int clearSourceCleanup(@Param("id") long id);

    @Select("SELECT * FROM img_asset WHERE status = 'DELETE_PENDING' AND (storage_key IS NOT NULL OR thumbnail_key IS NOT NULL) ORDER BY id LIMIT 100")
    List<ImageAsset> selectPendingDeletes();

    @Select("""
        SELECT * FROM img_asset
        WHERE deleted_at IS NOT NULL
          AND deleted_at < #{cutoff}
          AND (storage_key IS NOT NULL OR thumbnail_key IS NOT NULL)
        ORDER BY id
        LIMIT 500
        """)
    List<ImageAsset> selectCleanupCandidates(@Param("cutoff") LocalDateTime cutoff);

    @Update("""
        UPDATE img_asset
        SET storage_key = NULL, thumbnail_key = NULL, update_time = SYSDATE(),
            status = CASE WHEN status = 'DELETE_PENDING' THEN 'REJECTED' ELSE status END
        WHERE id = #{id}
        """)
    int clearStorageKeys(@Param("id") Long id);

    @Select("""
        <script>
        SELECT DISTINCT aspect_ratio FROM img_asset
        WHERE status = 'APPROVED' AND del_flag = '0' AND aspect_ratio IS NOT NULL
        <if test="all == false">AND create_by = #{userId}</if>
        ORDER BY aspect_ratio
        </script>
        """)
    List<String> selectApprovedAspectRatios(@Param("userId") Long userId, @Param("all") boolean all);
}
