package org.dromara.web.store;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.order.store.domain.StoreRenderBatch;
import org.dromara.order.store.domain.StoreRenderItem;
import org.dromara.order.store.mapper.StoreRenderBatchMapper;
import org.dromara.order.store.mapper.StoreRenderItemMapper;
import org.dromara.order.store.service.StoreObjectStorage;
import org.dromara.order.store.service.StoreRenderCleanup;
import org.dromara.order.store.service.StoreWorkflowService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("local")
class StoreRenderCleanupTimingTest {
    @Test void downloadedResultsRemainUntilThreeDayBoundaryThenShowExpiredStatus() throws Exception {
        assumeTrue("1".equals(System.getenv("RUN_CLEANUP_TDD")));
        String user = System.getenv("MYSQL_USER");
        String password = System.getenv("MYSQL_PASSWORD");
        String database = "cleanup_tdd_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String server = "jdbc:mysql://127.0.0.1:3306/";
        try (var connection = DriverManager.getConnection(server, user, password);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        try {
            var dataSource = new DriverManagerDataSource(server + database + "?serverTimezone=Asia/Shanghai", user, password);
            var jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("CREATE TABLE store_render_batch (id BIGINT PRIMARY KEY, owner_id BIGINT, status VARCHAR(24), downloaded_at DATETIME, create_time DATETIME)");
            jdbc.execute("CREATE TABLE store_render_item (id BIGINT PRIMARY KEY, batch_id BIGINT, status VARCHAR(24), lease_until DATETIME, result_oss_key VARCHAR(500), result_url VARCHAR(500), completed_at DATETIME)");
            jdbc.update("INSERT INTO store_render_batch VALUES (1,42,'COMPLETED',NULL,NOW()-INTERVAL 4 DAY)");
            String key = "store/results/42/00000000-0000-0000-0000-000000000001.jpg";
            jdbc.update("INSERT INTO store_render_item VALUES (2,1,'SUCCEEDED',NULL,?,NULL,NOW()-INTERVAL 4 DAY)", key);

            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), StoreRenderItem.class);
            StoreRenderBatchMapper batches = mock(StoreRenderBatchMapper.class);
            StoreRenderItemMapper items = mock(StoreRenderItemMapper.class);
            StoreObjectStorage storage = mock(StoreObjectStorage.class);
            when(batches.selectById(1L)).thenAnswer(ignored -> jdbc.queryForObject("SELECT id,owner_id,status,downloaded_at FROM store_render_batch WHERE id=1", (row, index) -> {
                var batch = new StoreRenderBatch();
                batch.setId(row.getLong("id")); batch.setOwnerId(row.getLong("owner_id")); batch.setStatus(row.getString("status"));
                var time = row.getTimestamp("downloaded_at"); batch.setDownloadedAt(time == null ? null : time.toLocalDateTime());
                return batch;
            }));
            when(batches.updateById(any(StoreRenderBatch.class))).thenAnswer(call -> {
                StoreRenderBatch batch = call.getArgument(0);
                return jdbc.update("UPDATE store_render_batch SET status=?, downloaded_at=? WHERE id=?", batch.getStatus(), batch.getDownloadedAt(), batch.getId());
            });
            when(items.selectCount(any())).thenAnswer(ignored -> jdbc.queryForObject("SELECT COUNT(*) FROM store_render_item WHERE batch_id=1 AND status IN ('SUCCEEDED','SKIPPED')", Long.class));
            AtomicInteger updates = new AtomicInteger();
            when(items.update(isNull(), any())).thenAnswer(ignored -> updates.getAndIncrement() == 0
                ? jdbc.update("UPDATE store_render_item SET status='RESULT_DELETED', result_url=NULL WHERE batch_id=1")
                : jdbc.update("UPDATE store_render_item SET result_oss_key=NULL WHERE batch_id=1"));
            var service = new StoreWorkflowService(null, null, null, null, batches, items, null, null,
                null, null, null, null, storage, jdbc, null);
            try (var login = mockStatic(LoginHelper.class)) {
                login.when(LoginHelper::getUserIdStr).thenReturn("42");
                service.confirmDownload(1L);
                assertNotNull(jdbc.queryForObject("SELECT downloaded_at FROM store_render_batch WHERE id=1", LocalDateTime.class));
                jdbc.update("UPDATE store_render_batch SET downloaded_at=NOW()-INTERVAL 3 DAY+INTERVAL 30 SECOND WHERE id=1");

                var timer = Executors.newSingleThreadScheduledExecutor();
                try {
                    timer.scheduleWithFixedDelay(new StoreRenderCleanup(service)::cleanup, 0, 1, TimeUnit.SECONDS);
                    Thread.sleep(10_000);
                    assertEquals("COMPLETED", jdbc.queryForObject("SELECT status FROM store_render_batch WHERE id=1", String.class));
                    verify(storage, never()).deleteRenderArchives(anyLong(), anyLong());
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(32);
                    while (System.nanoTime() < deadline && !"EXPIRED".equals(jdbc.queryForObject("SELECT status FROM store_render_batch WHERE id=1", String.class)))
                        Thread.sleep(1000);
                    assertEquals("EXPIRED", jdbc.queryForObject("SELECT status FROM store_render_batch WHERE id=1", String.class));
                    verify(storage).deleteRenderArchives(42L, 1L);
                    verify(storage).delete(key);
                    assertNull(jdbc.queryForObject("SELECT result_oss_key FROM store_render_item WHERE id=2", String.class));
                    assertThrows(ServiceException.class, () -> service.archiveFiles(1L, "ORIGINAL_NAME"));
                } finally {
                    timer.shutdownNow();
                }
            }
        } finally {
            try (var connection = DriverManager.getConnection(server, user, password);
                 var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE " + database);
            }
        }
    }
}
