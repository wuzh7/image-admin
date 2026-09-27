package org.dromara.order.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "tro.external")
public class TroProperties {
    @NotBlank(message = "TRO_API_KEY不能为空")
    private String apiKey;
}
