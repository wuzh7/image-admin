package org.dromara.image.mapper;

import org.apache.ibatis.annotations.*;
import org.dromara.image.domain.ImageRecords;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Mapper
public interface ImageRecordMapper {
    @Insert("""
        INSERT INTO img_replacement_log (id, old_image_id, new_image_id, user_id, user_name, old_storage_key, new_storage_key, create_time)
        VALUES (#{id}, #{oldImageId}, #{newImageId}, #{userId}, #{userName}, #{oldStorageKey}, #{newStorageKey}, NOW())
        """)
    int insertReplacement(ImageRecords.ReplacementLog log);

    @Select("""
        <script>SELECT * FROM img_replacement_log
        <if test="userName != null and userName != ''">WHERE user_name = #{userName}</if>
        ORDER BY create_time DESC, id DESC LIMIT 100</script>
        """)
    List<ImageRecords.ReplacementLog> replacements(@Param("userName") String userName);

    @Insert("INSERT IGNORE INTO img_image_export (user_id, image_id, request_id, completed, create_time) VALUES (#{userId}, #{imageId}, #{requestId}, 0, NOW())")
    int reserveExport(@Param("userId") long userId, @Param("imageId") long imageId, @Param("requestId") String requestId);

    @Update("UPDATE img_image_export SET completed = 1 WHERE request_id = #{requestId}")
    int completeExport(@Param("requestId") String requestId);

    @Delete("DELETE FROM img_image_export WHERE request_id = #{requestId} AND completed = 0")
    int releaseExport(@Param("requestId") String requestId);

    @Select("""
        <script>SELECT image_id FROM img_image_export WHERE user_id = #{userId} AND image_id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach></script>
        """)
    List<Long> exportedIds(@Param("userId") long userId, @Param("ids") List<Long> ids);

    @Insert("""
        INSERT INTO img_duplicate_todo (id, image_id, matched_image_id, similarity, scan_date, completed, create_time)
        VALUES (#{id}, #{left}, #{right}, #{similarity}, #{scanDate}, 0, NOW())
        ON DUPLICATE KEY UPDATE similarity = GREATEST(similarity, VALUES(similarity))
        """)
    int addTodo(@Param("id") long id, @Param("left") long left, @Param("right") long right,
                @Param("similarity") BigDecimal similarity, @Param("scanDate") LocalDate scanDate);

    @Select("""
        <script>SELECT t.*, u.user_name, m.user_name matched_user_name FROM img_duplicate_todo t
        JOIN img_asset a ON a.id = t.image_id JOIN img_asset b ON b.id = t.matched_image_id
        LEFT JOIN sys_user u ON u.user_id = a.create_by LEFT JOIN sys_user m ON m.user_id = b.create_by
        WHERE t.completed = 0
        <if test="userName != null and userName != ''">AND (u.user_name = #{userName} OR m.user_name = #{userName})</if>
        ORDER BY t.create_time, t.id LIMIT 100</script>
        """)
    List<ImageRecords.DuplicateTodo> todos(@Param("userName") String userName);

    @Update("UPDATE img_duplicate_todo SET completed = 1, completed_by = #{userId}, completed_at = NOW() WHERE id = #{id} AND completed = 0")
    int completeTodo(@Param("id") long id, @Param("userId") long userId);

    @Select("SELECT id FROM img_library_mutex WHERE id = 2 FOR UPDATE")
    Long lockDailyScan();

    @Select("""
        SELECT COUNT(*) FROM img_duplicate_scan_run r WHERE r.scan_date = #{date}
        AND NOT EXISTS (SELECT 1 FROM img_asset a WHERE a.status = 'APPROVED' AND a.del_flag = '0'
          AND a.create_time >= #{date} AND a.create_time < DATE_ADD(#{date}, INTERVAL 1 DAY)
          AND a.approved_at >= r.completed_at)
        """)
    int scanCompleted(@Param("date") LocalDate date);

    @Select("""
        SELECT DISTINCT DATE(a.create_time) FROM img_asset a
        LEFT JOIN img_duplicate_scan_run r ON r.scan_date = DATE(a.create_time)
        WHERE a.status = 'APPROVED' AND a.del_flag = '0' AND a.create_time < #{today}
          AND (r.scan_date IS NULL OR a.approved_at >= r.completed_at)
        ORDER BY DATE(a.create_time) LIMIT 10
        """)
    List<LocalDate> pendingScanDates(@Param("today") LocalDate today);

    @Insert("INSERT INTO img_duplicate_scan_run (scan_date, completed_at) VALUES (#{date}, #{startedAt}) ON DUPLICATE KEY UPDATE completed_at = #{startedAt}")
    int completeScan(@Param("date") LocalDate date, @Param("startedAt") java.time.LocalDateTime startedAt);

    @Delete("DELETE FROM img_replacement_log") int clearReplacements();
    @Delete("DELETE FROM img_image_export") int clearExports();
    @Delete("DELETE FROM img_duplicate_todo") int clearTodos();
    @Delete("DELETE FROM img_duplicate_scan_run") int clearScans();
}
