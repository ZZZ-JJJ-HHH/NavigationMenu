package com.example.navigation_menu.service.chain.handler;

import com.example.navigation_menu.model.vo.MenuNodeVO;
import com.example.navigation_menu.model.vo.MenuTreeVO;
import com.example.navigation_menu.service.MenuTreeBuilder;
import com.example.navigation_menu.service.chain.MenuContext;
import com.example.navigation_menu.service.chain.MenuHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Order(5)
@RequiredArgsConstructor
@Slf4j
public class TreeBuildHandler implements MenuHandler {

    private final MenuTreeBuilder treeBuilder;

    @Override
    public void handle(MenuContext context) {
        List<MenuNodeVO> tree = treeBuilder.build(context.getFiltered());
        context.setTree(tree);
        context.setResult(new MenuTreeVO(tree, null));
        log.info("tree built, root count: {}", tree.size());
    }
}