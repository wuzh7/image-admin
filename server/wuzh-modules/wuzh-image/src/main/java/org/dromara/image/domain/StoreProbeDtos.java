package org.dromara.image.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public final class StoreProbeDtos {
    private StoreProbeDtos() {
    }

    public record SaveRequest(Long id,
                              @NotBlank(message = "店铺ID不能为空") @Size(max = 128) String storeId,
                              @Size(max = 255) String storeName,
                              @Size(max = 500) String remarks) {
    }

    public record View(Long id, String storeId, String storeName, String remarks,
                       LocalDateTime createTime, LocalDateTime updateTime) {
    }
}
