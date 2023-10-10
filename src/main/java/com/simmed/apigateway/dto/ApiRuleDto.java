package com.simmed.apigateway.dto;

import lombok.Data;

@Data
public class ApiRuleDto {
    private String apiId;
    private String fieldName;
    private Integer ruleId;
    private String userId;
    private String appId;
    private String appType;
}