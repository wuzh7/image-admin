package org.dromara.image.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.image.config.ImageProperties;
import org.dromara.image.domain.ImageAsset;
import org.dromara.image.mapper.ImageAssetMapper;
import org.dromara.image.mapper.ImageRecordMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImageDailyDuplicateScan {
    private final ImageAssetMapper assets;
    private final ImageRecordMapper records;
    private final ImageDuplicateChecker checker;
    private final ImageProperties properties;
    private final PlatformTransactionManager transactions;
    private final ImageDataLock dataLock;

    // 固定检查也用于重启后的补跑；成功日期持久化，失败不能记录为完成。
    @Scheduled(fixedDelay = 60000, scheduler = "imageDailyScanScheduler")
    public void dispatch() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        if (LocalTime.now(zone).isBefore(LocalTime.of(0, 10))) return;
        try {
            dataLock.read(() -> records.pendingScanDates(LocalDate.now(zone)).forEach(this::scan));
        } catch (RuntimeException exception) {
            log.error("每日图片复查失败，将重试", exception);
        }
    }

    public void scan(LocalDate date) {
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.executeWithoutResult(status -> {
            if (records.lockDailyScan() == null) throw new IllegalStateException("每日复查互斥锁未初始化");
            if (records.scanCompleted(date) > 0) return;
            // 以开始时间为界，扫描期间才入库的历史图片必须在下一轮补查。
            var startedAt = java.time.LocalDateTime.now().withNano(0);
            long cursor = 0;
            while (true) {
                var page = assets.selectList(Wrappers.<ImageAsset>lambdaQuery().eq(ImageAsset::getStatus, "APPROVED")
                    .ge(ImageAsset::getCreateTime, date.atStartOfDay()).lt(ImageAsset::getCreateTime, date.plusDays(1).atStartOfDay())
                    .gt(ImageAsset::getId, cursor).orderByAsc(ImageAsset::getId).last("LIMIT 100"));
                if (page.isEmpty()) break;
                for (ImageAsset image : page) {
                    for (var match : checker.check(image, null)) {
                        if (match.similarity().compareTo(properties.getSimilarityThreshold()) < 0) continue;
                        records.addTodo(IdWorker.getId(), Math.min(image.getId(), match.imageId()),
                            Math.max(image.getId(), match.imageId()), match.similarity(), date);
                    }
                }
                cursor = page.getLast().getId();
            }
            records.completeScan(date, startedAt);
        });
    }
}
