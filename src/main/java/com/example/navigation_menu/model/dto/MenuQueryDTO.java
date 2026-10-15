package com.example.navigation_menu.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MenuQueryDTO {

    @NotBlank(message = "国家编码不能为空")
    private String countryCode;

    @NotBlank(message = "实体编码不能为空")
    private String entityCode;

    @NotBlank(message = "渠道编码不能为空")
    private String channelCode;

    @NotBlank(message = "分行编码不能为空")
    private String branchCode;

    private String userId;

    private String targetPath;
}