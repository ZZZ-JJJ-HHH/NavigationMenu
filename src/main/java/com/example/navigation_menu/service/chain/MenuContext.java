package com.example.navigation_menu.service.chain;

import com.example.navigation_menu.model.dto.MenuQueryDTO;
import com.example.navigation_menu.model.entity.MenuConfig;
import com.example.navigation_menu.model.vo.MenuNodeVO;
import com.example.navigation_menu.model.vo.MenuTreeVO;
import lombok.Data;

import java.util.List;
import java.util.Set;

@Data
public class MenuContext {

    private MenuQueryDTO query;

    private List<MenuConfig> allMenus;

    private List<MenuConfig> matched;

    private Set<String> userPerms;

    private List<MenuConfig> filtered;

    private List<MenuNodeVO> tree;

    private MenuTreeVO result;
}