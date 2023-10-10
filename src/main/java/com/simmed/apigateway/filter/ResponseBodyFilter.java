package com.simmed.apigateway.filter;

import brave.Tracer;
import com.aayushatharva.brotli4j.Brotli4jLoader;
import com.aayushatharva.brotli4j.decoder.Decoder;
import com.aayushatharva.brotli4j.decoder.DecoderJNI;
import com.aayushatharva.brotli4j.decoder.DirectDecompress;
import com.aayushatharva.brotli4j.encoder.Encoder;
import com.alibaba.fastjson.JSON;
import com.simmed.apigateway.dto.ApiRuleDto;
import com.simmed.apigateway.dto.ExecDatamaskByApiRuleRequest;
import com.simmed.apigateway.utils.DataMaskUtil;
import com.simmed.apigateway.utils.RedisUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.ORIGINAL_RESPONSE_CONTENT_TYPE_ATTR;


@Slf4j
@Component
public class ResponseBodyFilter implements GlobalFilter, GatewayFilter, Ordered {

    private static final String CACHE_REQUEST_URL_OBJECT_KEY = "cachedRequestUrlObject";
    private static final String CACHE_REQUEST_BODY_OBJECT_KEY = "cachedRequestBodyObject";
    private static final String CACHE_REQUEST_API_ID = "cachedRequestApiId";
    private static final String ApiDataMaskCacheKey = "_ApiDataMaskRules";

    @Autowired
    Tracer tracer;
    @Autowired
    DataMaskUtil dataMaskUtil;
    @Autowired
    RedisUtil redisUtil;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().toString();
        ServerHttpResponse originalResponse = exchange.getResponse();
        DataBufferFactory bufferFactory = originalResponse.bufferFactory();
        String acceptEncoding = request.getHeaders().getFirst("Accept-Encoding");

        ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {

                if (getStatusCode().equals(HttpStatus.OK) && body instanceof Flux) {
                    // 获取ContentType，判断是否返回JSON格式数据
                    String originalResponseContentType = exchange.getAttribute(ORIGINAL_RESPONSE_CONTENT_TYPE_ATTR);
                    String originalResponseContentEncoding = originalResponse.getHeaders().getFirst("content-encoding");
                    HttpHeaders headers = originalResponse.getHeaders();
                    if (StringUtils.isNotBlank(originalResponseContentType) && originalResponseContentType.contains("application/json")) {
                        Flux<? extends DataBuffer> fluxBody = (Flux<? extends DataBuffer>) body;
                        return super.writeWith(fluxBody.buffer().map(dataBuffer -> {

                            DataBufferFactory dataBufferFactory = new DefaultDataBufferFactory();
                            DataBuffer join = dataBufferFactory.join(dataBuffer);
                            byte[] content = new byte[join.readableByteCount()];
                            join.read(content);

                            if (tracer != null && tracer.currentSpan() != null) {
                                tracer.currentSpan().tag("apigateway.response.encoding", originalResponseContentEncoding == null ? "None" : originalResponseContentEncoding);
                            }
                            //释放掉内存
                            DataBufferUtils.release(join);
                            String s = null;
                            //返回 Content-Encoding: br 压缩格式
                            if (!StringUtils.isBlank(originalResponseContentEncoding) && originalResponseContentEncoding.equals("br")) {
                                log.debug("Brotli Response Content-Encoding:{}", originalResponseContentEncoding);
                                log.debug("Brotli Before UnCompress Data Length:{}", content.length);
                                try {
                                    Brotli4jLoader.ensureAvailability();
                                    DirectDecompress directDecompress = Decoder.decompress(content); // or DirectDecompress.decompress(compressed);
                                    if (directDecompress.getResultStatus() == DecoderJNI.Status.DONE) {
                                        s = new String(directDecompress.getDecompressedData());
                                    } else {
                                        log.error("Some Error Occurred While Decompressing");
                                    }
                                } catch (IOException e) {
                                    log.error("Brotli UnCompress Error!");
                                    e.printStackTrace();
                                }
                            } else {
                                //返回 gzip 压缩格式
                                if (!StringUtils.isBlank(acceptEncoding) && acceptEncoding.contains("gzip")) {
                                    s = new String(uncompress(content), StandardCharsets.UTF_8);
                                } else {
                                    //不压缩
                                    s = new String(content, StandardCharsets.UTF_8);
                                }
                            }

                            String requestJson = exchange.getAttribute(CACHE_REQUEST_BODY_OBJECT_KEY);
                            String urlparams = exchange.getAttribute(CACHE_REQUEST_URL_OBJECT_KEY);

                            //记录请求日志
                            log.debug("<<<------ResponseBodyFilter----->>>");
                            log.debug("path:{}", path);
                            log.debug("params:{}", urlparams);
                            log.debug("request:{}", requestJson);
                            log.debug("response:{}", s);

                            //调用脱敏服务
                            if (!StringUtils.isBlank(s)) {
                                log.debug("Exec Datamask By ApiRule Start:{}", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date()));
                                String token = exchange.getRequest().getHeaders().getFirst("WeAppAuthorization");
                                Object obj = redisUtil.get(token + ApiDataMaskCacheKey);
                                if (obj != null) {
                                    List<ApiRuleDto> apiRuleDtos = JSON.parseArray(obj.toString(), ApiRuleDto.class);
                                    String apiId = exchange.getAttribute(CACHE_REQUEST_API_ID);
                                    List<ApiRuleDto> rules = apiRuleDtos.stream().filter(p -> p.getApiId().equals(apiId)).collect(Collectors.toList());
                                    if (!CollectionUtils.isEmpty(rules)) {
                                        ExecDatamaskByApiRuleRequest datamaskByApiRuleRequest = new ExecDatamaskByApiRuleRequest();
                                        datamaskByApiRuleRequest.setValue(s);
                                        datamaskByApiRuleRequest.setApiRules(rules);
                                        s = dataMaskUtil.execDatamaskByApiRule(datamaskByApiRuleRequest);
                                    }
                                }
                                log.debug("Exec Datamask By ApiRule End:{}", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date()));
                            }

                            log.debug("s: {}", s);

                            byte[] uppedContent = null;
                            if (StringUtils.isBlank(s)) {
                                log.debug("Content Length:{}, Response isBlank,UnCompress Error!", content.length);
                            } else {
                                if (!StringUtils.isBlank(originalResponseContentEncoding) && originalResponseContentEncoding.equals("br")) {
                                    try {
                                        Brotli4jLoader.ensureAvailability();
                                        uppedContent = Encoder.compress(s.getBytes());
                                        log.debug("Brotli Compress OK! Data Length:{}", uppedContent.length);
                                    } catch (Exception e) {
                                        log.error("Brotli Compress Error!");
                                        e.printStackTrace();
                                    }
                                } else {
                                    if (!StringUtils.isBlank(acceptEncoding) && acceptEncoding.contains("gzip")) {
                                        uppedContent = compress(s, StandardCharsets.UTF_8.toString());
                                    } else {
                                        uppedContent = s.getBytes(StandardCharsets.UTF_8);
                                    }
                                }
                            }
                            if (uppedContent.length > 0L) {
                                headers.setContentLength(uppedContent.length);
                            } else {
                                headers.set("Transfer-Encoding", "chunked");
                            }
                            if (tracer != null && tracer.currentSpan() != null) {
                                tracer.currentSpan().tag("apigateway.response.contentLength", String.valueOf(uppedContent.length));
                            }
                            return bufferFactory.wrap(uppedContent);
                        }));
                    }
                }
                // if body is not a flux. never got there.
                return super.writeWith(body);
            }
        };
        // replace response with decorator
        return chain.filter(exchange.mutate().response(decoratedResponse).build());
    }

    @Override
    public int getOrder() {
        //必须小于-1 才能进方法。
        return -2;
        //系统全局过滤器执行顺序（名称、order）
        //RemoveCachedBodyFilter  HIGHEST_PRECEDENCE = Integer.MIN_VALUE
        //AdaptCachedBodyGlobalFilter HIGHEST_PRECEDENCE = Integer.MIN_VALUE
        //NettyWriteResponseFilter -1
        //ForwardPathFilter 0
        //GatewayMetricsFilter 0
        //RouteToRequestUrlFilter 10000
        //WeightCalculatorWebFilter 10001
        //LoadBalancerClientFilter 10100
        //WebsocketRoutingFilter LOWEST_PRECEDENCE -1
        //NettyRoutingFilter LOWEST_PRECEDENCE =Integer.MAX_VALUE
        //ForwardRoutingFilter LOWEST_PRECEDENCE =Integer.MAX_VALUE
    }

    /*解码 gzip*/
    public static byte[] uncompress(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        try {
            GZIPInputStream ungzip = new GZIPInputStream(in);
            byte[] buffer = new byte[256];
            int n;
            while ((n = ungzip.read(buffer)) >= 0) {
                out.write(buffer, 0, n);
            }
        } catch (IOException e) {
            log.error("gzip uncompress error.", e);
        }

        return out.toByteArray();
    }

    /*编码 gzip*/
    public static byte[] compress(String str, String encoding) {
        if (str == null || str.length() == 0) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        GZIPOutputStream gzip;
        try {
            gzip = new GZIPOutputStream(out);
            gzip.write(str.getBytes(encoding));
            gzip.close();
        } catch (IOException e) {
            log.error("gzip compress error.", e);
        }

        return out.toByteArray();
    }
}
