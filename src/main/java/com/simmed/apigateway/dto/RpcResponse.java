package com.simmed.apigateway.dto;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class RpcResponse<T> {
    private String id;
    @Builder.Default
    private String jsonrpc = "2.0";
    private Error error;
    private T result;

    //.net接口返回的2个字段
    private String code;
    private String message;
}