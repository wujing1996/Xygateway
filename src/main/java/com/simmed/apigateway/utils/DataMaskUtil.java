package com.simmed.apigateway.utils;

import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.simmed.apigateway.dto.ExecDatamaskByApiRuleRequest;
import com.simmed.apigateway.dto.RpcRequest;
import com.simmed.apigateway.dto.RpcResponse;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

@Component
public class DataMaskUtil {
    @Autowired
    private RestTemplate restTemplate;
    @Value("${spring.cloud.nacos.discovery.group:DEFAULT_GROUP}")
    private String group;
    @Autowired
    private NamingService namingService;

    private final String dataId = "datamask";
    private final String serviceUrl = "/api/datamask/masking";
    private final String rpcMethod = "execDatamaskByApiRule";

    private String getUrl() {

        String url = "";
        Instance ins = null;
        try {
            ins = namingService.selectOneHealthyInstance(dataId, group);
        } catch (Exception e) {
            System.out.print(e.getStackTrace());
            System.out.print("instance discovery error,在群组: '" + group + "'中无法获取实例: '" + dataId + "'");
        }
        if (ins == null || !ins.isHealthy()) {
            System.out.print("instance discovery error,在群组: '" + group + "'中无健康实例: '" + dataId + "'");
        } else {
            url = String.format("http://%s:%d%s", ins.getIp(), ins.getPort(), serviceUrl);
        }
        return url;
    }

    public String execDatamaskByApiRule(ExecDatamaskByApiRuleRequest request) {
        String url = getUrl();
        if (StringUtils.isBlank(url)) {
            return request.getValue();
        }
        List<Object> list = new ArrayList<>();
        list.add(request);
        RpcRequest req = RpcRequest.builder().method(rpcMethod).params(list).build();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            ResponseEntity<RpcResponse> ret = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(req, headers), RpcResponse.class);
            return ret.getBody().getResult().toString();
        } catch (Exception ex) {
            System.out.print(ex.getStackTrace());
            System.out.print("execDatamaskByApiRule error, message: " + ex.getMessage());
            return request.getValue();
        }
    }
}
