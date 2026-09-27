package org.dromara.order.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({OrderProperties.class, TroProperties.class})
public class OrderConfiguration {
}
