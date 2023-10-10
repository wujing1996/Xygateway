package com.simmed.apigateway.dto;

import lombok.Data;

@Data
public class GatewayApiDto {
    private String apiId ;
    private String apiName;
    private Integer loginCheck ;
    private Integer rpcCheck;
    private Integer isOpen;
    private String serviceName;
    private String serviceGroup ;
    private Integer serviceIsOpen ;
    private String moduleName ;
    private Integer moduleIsOpen ;
    private String path ;
}
