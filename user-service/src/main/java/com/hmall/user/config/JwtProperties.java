package com.hmall.user.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "hm.jwt")
public class JwtProperties {
    private Resource location;
    private String password;
    //化名
    private String alias;
    //java.time 设置10分钟的ttl
    private Duration tokenTTL = Duration.ofMinutes(10);
}
