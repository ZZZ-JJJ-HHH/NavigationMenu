package com.example.navigation_menu.service.chain.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.navigation_menu.mapper.MenuConfigMapper;
import com.example.navigation_menu.model.entity.MenuConfig;
import com.example.navigation_menu.service.chain.MenuContext;
import com.example.navigation_menu.service.chain.MenuHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Order(2)
@RequiredArgsConstructor
@Slf4j
public class LoadMenuHandler implements MenuHandler {

    private final MenuConfigMapper menuConfigMapper;

    @Override
    public void handle(MenuContext context) {
        List<MenuConfig> allMenus = menuConfigMapper.selectList(
                new LambdaQueryWrapper<MenuConfig>()
                        .eq(MenuConfig::getIsActive, 1)
        );
        context.setAllMenus(allMenus);
        log.info("loaded {} active menus", allMenus.size());
    }
}