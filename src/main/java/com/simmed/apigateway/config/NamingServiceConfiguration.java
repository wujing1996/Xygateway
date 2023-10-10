package com.simmed.apigateway.config;

import com.alibaba.cloud.nacos.ConditionalOnNacosDiscoveryEnabled;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.util.Properties;

@Configuration
public class NamingServiceConfiguration {

    @Value("${spring.cloud.nacos.discovery.server-addr:}")
    private String serverAddr;
    @Value("${spring.cloud.nacos.discovery.namespace:}")
    private String namespace;

    @Bean
    @Order
    @ConditionalOnMissingBean(NamingService.class)
    @ConditionalOnNacosDiscoveryEnabled
    public NamingService namingService() throws NacosException {

        Properties properties = new Properties();
        properties.setProperty("serverAddr", serverAddr);
        properties.setProperty("namespace", namespace);

        return NamingFactory.createNamingService(properties);
    }
}
