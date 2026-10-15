package com.example.navigation_menu.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MenuTreeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<MenuNodeVO> menuTree;

    private List<BreadcrumbItemVO> breadcrumb;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BreadcrumbItemVO implements Serializable {

        private static final long serialVersionUID = 1L;

        private String nodeId;

        private String nodeName;

        private String nodePath;
    }
}