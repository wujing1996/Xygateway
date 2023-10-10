package com.simmed.apigateway.dto;

import lombok.*;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class RpcRequest {
    @Builder.Default
    private String id = System.currentTimeMillis() + "";
    @Builder.Default
    private String jsonrpc = "2.0";
    private String method;
    @Singular("param")
    private List<Object> params;
}
