package com.simmed.apigateway.filter;

import brave.Tracer;
import com.alibaba.fastjson.JSON;
import com.simmed.apigateway.dto.GatewayApiDto;
import com.simmed.apigateway.utils.RedisUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
public class AuthAndLoginFilter implements GlobalFilter, GatewayFilter, Ordered {

    @Autowired
    RedisUtil redisUtil;

    @Autowired
    Tracer tracer;

    private static final String CACHE_REQUEST_BODY_OBJECT_KEY = "cachedRequestBodyObject";
    private static final String CACHE_REQUEST_API_ID = "cachedRequestApiId";
    private static final String CACHE_REDIS_APIS = "SIMMED_GatewayApi_CheckList";
    private static final String CACHE_REDIS_Permissions = "SIMMED_GatewayApi_PermissionList";
    private static final String PermissionCacheKey = "_PermissionApis";


    @Override
    @SuppressWarnings("unchecked")
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse resp = exchange.getResponse();

        //只处理 http 请求(包含 https)
        String schema = request.getURI().getScheme();
        if ((!"http".equals(schema) && !"https".equals(schema))) {
            return chain.filter(exchange);
        }

        String path = request.getPath().toString();
        String method = request.getMethodValue();

        log.debug("get method from request body:{}", "start");
        String requestDataStr = exchange.getAttribute(CACHE_REQUEST_BODY_OBJECT_KEY);
        if (!StringUtils.isBlank(requestDataStr)) {
            try {
                RequestData<Object> requestData = JSON.parseObject(requestDataStr, RequestData.class);
                if (requestData != null && !StringUtils.isBlank(requestData.getMethod())) {
                    method = requestData.getMethod();
                }
            } catch (Exception e) {
                log.error("RequestData parseObject error", e);
                log.debug("Get method from request body error:{}", "python project!");
            }
        }

        if (tracer != null && tracer.currentSpan() != null) {
            tracer.currentSpan().tag("apigateway.request.method", method);
        }

        try {
            List<GatewayApiDto> apis;
            Object cacheApis = redisUtil.get(CACHE_REDIS_APIS);
            if (cacheApis != null) {
                apis = JSON.parseArray(cacheApis.toString(), GatewayApiDto.class);
                log.debug("Load CACHE_REDIS_APIS Success!");
            } else {
                log.debug("Get CACHE_REDIS_APIS Error:{}", "need init doc apis!");
                return authError(resp, "1", "请初始化文档!");
            }

            String finalMethod = method;
            GatewayApiDto api = null;
            Optional<GatewayApiDto> optional = apis.stream().filter(x -> x.getPath().equalsIgnoreCase(path) && x.getApiName().equalsIgnoreCase(finalMethod)).findFirst();
            if (optional.isPresent()) {
                api = optional.get();
            }
            String token = exchange.getRequest().getHeaders().getFirst("WeAppAuthorization");

            if (tracer != null && tracer.currentSpan() != null) {
                tracer.currentSpan().tag("apigateway.request.weAppAuthorization", token == null ? "Not Logged In" : token);
            }

            //需要登录拦截的API
            if (api != null) {
                log.debug("need check login path:{}", path);
                log.debug("need check login method:{}", finalMethod);
                if (StringUtils.isBlank(token)) {
                    return authError(resp, "1", "请登陆后再访问!");
                }
                log.debug("WeAppAuthorization SessionKey:{}", token);
                if (!redisUtil.hasKey(token)) {
                    log.debug("SessionKey Timeout:{}", token);
                    return authError(resp, "1", "请登陆后再访问!");
                }
                exchange.getAttributes().put(CACHE_REQUEST_API_ID, api.getApiId());
            }

            List<GatewayApiDto> permissionsApis = new ArrayList<>();
            Object perApis = redisUtil.get(CACHE_REDIS_Permissions);
            if (perApis != null) {
                permissionsApis = JSON.parseArray(perApis.toString(), GatewayApiDto.class);
                log.debug("Load CACHE_REDIS_Permissions Success!");
            }
            GatewayApiDto perApi = null;
            Optional<GatewayApiDto> preOptional = permissionsApis.stream().filter(x -> x.getPath().equalsIgnoreCase(path) && x.getApiName().equalsIgnoreCase(finalMethod)).findFirst();
            if (preOptional.isPresent()) {
                perApi = preOptional.get();
            }
            if (perApi != null) {
                Object obj = redisUtil.get(token + PermissionCacheKey);
                if (obj != null) {
                    List<String> perList = JSON.parseArray(obj.toString(), String.class);
                    String apiId = perApi.getApiId();
                    if (!perList.stream().filter(x -> x.equalsIgnoreCase(apiId)).findFirst().isPresent()) {
                        return authError(resp, "1", "无访问权限!");
                    }
                } else {
                    log.debug("User's Permissions un init,Try Change Current AppId:{}", token + PermissionCacheKey);
                    return authError(resp, "1", "无访问权限!");
                }
            }

        } catch (Exception e) {
            log.error("AuthAndLoginFilter error", e);
            return authError(resp, "1", "系统繁忙，请稍后再试!");
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return -9;
    }

    /**
     * 认证错误输出
     *
     * @param resp 响应对象
     * @param mess 错误信息
     * @return
     */
    private Mono<Void> authError(ServerHttpResponse resp, String id, String mess) {
        resp.setStatusCode(HttpStatus.UNAUTHORIZED);
        resp.getHeaders().add("Content-Type", "application/json;charset=UTF-8");
        ReturnData returnData = new ReturnData(id, 500, mess);
        String returnStr = "";

        if (tracer != null && tracer.currentSpan() != null) {
            tracer.currentSpan().tag("apigateway.request.error", mess);
        }

        try {
            returnStr = JSON.toJSONString(returnData);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        DataBuffer buffer = resp.bufferFactory().wrap(returnStr.getBytes(StandardCharsets.UTF_8));
        return resp.writeWith(Flux.just(buffer));
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public class ReturnData {
        private String id;
        private Integer code;
        private String message;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class RequestData<T> {
        private String id;
        private String jsonrpc;
        private String method;
        private List<T> params;
    }
}
