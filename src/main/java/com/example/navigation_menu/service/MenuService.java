package com.example.navigation_menu.service;

import com.example.navigation_menu.model.dto.MenuQueryDTO;
import com.example.navigation_menu.model.vo.MenuTreeVO;

public interface MenuService {

    MenuTreeVO buildMenuTree(MenuQueryDTO queryDTO);
}