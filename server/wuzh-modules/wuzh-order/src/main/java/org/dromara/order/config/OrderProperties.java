package org.dromara.order.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "order.external")
public class OrderProperties {

    @NotBlank(message = "ORDER_DETAIL_API_KEY不能为空")
    private String apiKey;
}
