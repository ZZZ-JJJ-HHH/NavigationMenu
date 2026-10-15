package com.example.navigation_menu.controller;

import com.example.navigation_menu.model.dto.MenuQueryDTO;
import com.example.navigation_menu.model.vo.MenuTreeVO;
import com.example.navigation_menu.model.vo.Result;
import com.example.navigation_menu.service.MenuService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/menu")
@RequiredArgsConstructor
@Slf4j
public class MenuController {

    private final MenuService menuService;

    @PostMapping("/tree")
    public Result<MenuTreeVO> getMenuTree(@RequestBody @Valid MenuQueryDTO queryDTO) {
        log.info("getMenuTree, params: {}", queryDTO);
        MenuTreeVO tree = menuService.buildMenuTree(queryDTO);
        return Result.success(tree);
    }
}