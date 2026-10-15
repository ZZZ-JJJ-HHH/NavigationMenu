package com.example.navigation_menu.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class MenuNodeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String nodeId;

    private String nodeName;

    private String nodePath;

    private String nodeIcon;

    private Integer sortOrder;

    private List<MenuNodeVO> children;
}