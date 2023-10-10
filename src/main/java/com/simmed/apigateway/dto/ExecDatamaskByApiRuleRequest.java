package com.simmed.apigateway.dto;

import lombok.Data;

import java.util.List;

@Data
public class ExecDatamaskByApiRuleRequest {

    private String value;
    private List<ApiRuleDto> apiRules;

}
