package com.example.navigation_menu.model.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("menu_config")
public class MenuConfig {

    private Long id;

    private String countryCode;

    private String entityCode;

    private String channelCode;

    private String branchCode;

    private Long parentId;

    private String nodeId;

    private String nodeName;

    private String nodePath;

    private String nodeIcon;

    private Integer sortOrder;

    private String requiredPerm;

    private Integer isActive;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}