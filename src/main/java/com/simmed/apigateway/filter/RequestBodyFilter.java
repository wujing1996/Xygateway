package com.simmed.apigateway.filter;

import brave.Tracer;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.factory.rewrite.CachedBodyOutputMessage;
import org.springframework.cloud.gateway.support.BodyInserterContext;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.PooledDataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URLDecoder;
import java.util.*;

@Slf4j
@Component
public class RequestBodyFilter implements GlobalFilter, GatewayFilter, Ordered {

    @Autowired
    ServerCodecConfigurer codeConfig;

    @Autowired
    Tracer tracer;

    private static final String CACHE_REQUEST_BODY_OBJECT_KEY = "cachedRequestBodyObject";
    private static final String CACHE_REQUEST_URL_OBJECT_KEY = "cachedRequestUrlObject";
    private static final Set<String> SUPPORTED_MEDIA_TYPES = new HashSet<>(Arrays.asList(
            MediaType.MULTIPART_FORM_DATA.toString(),
            MediaType.APPLICATION_OCTET_STREAM.toString(),
            MediaType.TEXT_PLAIN.toString()
    ));

    @Override
    @SuppressWarnings("unchecked")
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        Class inClass = String.class;
        ServerRequest serverRequest = ServerRequest.create(exchange, codeConfig.getReaders());

        ServerHttpRequest request = exchange.getRequest();

        //只记录 http 请求(包含 https)
        String schema = request.getURI().getScheme();
        if ((!"http".equals(schema) && !"https".equals(schema))) {
            return chain.filter(exchange);
        }

        String contentType = request.getHeaders().getFirst("Content-Type");
        //String upload = request.getHeaders().getFirst("upload");
        //获取URL传参
        if (!request.getQueryParams().isEmpty()) {
            Map<String, String> map = new HashMap<>();
            request.getQueryParams().toSingleValueMap().forEach((key, val) -> map.put(key, getURLDecoder(val)));
            String param = JSON.toJSONString(map);
            exchange.getAttributes().put(CACHE_REQUEST_URL_OBJECT_KEY, param);
        }
        if (tracer != null && tracer.currentSpan() != null) {
            tracer.currentSpan().tag("apigateway.request.id", request.getId());
            tracer.currentSpan().tag("apigateway.request.contentType", contentType == null ? "None" : contentType);
        }

        //没有内容类型不读取body
        if (contentType == null || contentType.length() == 0 ) {
            return chain.filter(exchange);
        }
        //文件上传不读取body
        //if ("true".equals(upload)) {
        contentType=contentType.split(";")[0].toLowerCase();
        if (SUPPORTED_MEDIA_TYPES.contains(contentType)) {
            return chain.filter(exchange);
        }

        Mono<?> modifiedBody = serverRequest.bodyToMono(inClass).flatMap(o -> {
            exchange.getAttributes().put(CACHE_REQUEST_BODY_OBJECT_KEY, o);
            return Mono.justOrEmpty(o);
        });

        BodyInserter bodyInserter = BodyInserters.fromPublisher(modifiedBody, inClass);
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(exchange.getRequest().getHeaders());
        CachedBodyOutputMessage outputMessage = new CachedBodyOutputMessage(exchange, headers);

        return bodyInserter.insert(outputMessage, new BodyInserterContext())
                .then(Mono.defer(() -> {
                    ServerHttpRequestDecorator decorator = new ServerHttpRequestDecorator(exchange.getRequest()) {
                        @Override
                        public HttpHeaders getHeaders() {
                            long contentLength = headers.getContentLength();
                            HttpHeaders httpHeaders = new HttpHeaders();
                            httpHeaders.putAll(super.getHeaders());
                            if (contentLength > 0) {
                                httpHeaders.setContentLength(contentLength);
                            } else {
                                //this causes a 'HTTP/1.1 411 Length Required'
                                httpHeaders.set(HttpHeaders.TRANSFER_ENCODING, "chunked");
                            }
                            return httpHeaders;
                        }

                        //释放 DataBuffer 对象，以避免潜在的内存泄漏问题
                        @Override
                        public Flux<DataBuffer> getBody() {
                            return outputMessage.getBody()
                                    .doOnDiscard(PooledDataBuffer.class, DataBufferUtils::release);
                        }
                    };
                    return chain.filter(exchange.mutate().request(decorator).build());
                }));
    }

    @Override
    public int getOrder() {
        return -10;
    }

    /**
     * URL 转码
     *
     * @param val url
     * @return string
     */
    private String getURLDecoder(String val) {
        try {
            return URLDecoder.decode(val, "utf-8");
        } catch (Exception e) {
            log.error("getURLDecoder error", e);
        }
        return val;
    }
}
