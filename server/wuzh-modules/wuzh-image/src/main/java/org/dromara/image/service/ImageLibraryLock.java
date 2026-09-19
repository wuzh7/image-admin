package org.dromara.image.service;

import lombok.RequiredArgsConstructor;
import org.dromara.image.mapper.ImageAssetMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class ImageLibraryLock {
    private final ImageAssetMapper assets;
    private final PlatformTransactionManager transactions;

    public <T> T run(Supplier<T> action) {
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction.execute(status -> {
            if (assets.lockLibrary() == null) {
                throw new org.dromara.common.core.exception.ServiceException("图片互斥锁未初始化，请先执行数据库迁移");
            }
            return action.get();
        });
    }
}
