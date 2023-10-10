package com.simmed.apigateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayConfig {

    public static String NACOS_DATA_ID;
    public static String NACOS_GROUP_ID;

    @Value("${gateway.dynamicRoute.dataId}")
    public void setNacosDataId(String dataId) {
        NACOS_DATA_ID = dataId;
    }

    @Value("${gateway.dynamicRoute.group}")
    public void setNacosGroupId(String group) {
        NACOS_GROUP_ID = group;
    }
}
