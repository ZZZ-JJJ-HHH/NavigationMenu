package com.example.navigation_menu.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("branch_config")
public class BranchConfig {

    private Long id;

    private String branchCode;

    private String branchName;

    private String countryCode;

    private Integer useLegacyMenu;

    private Integer isActive;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}