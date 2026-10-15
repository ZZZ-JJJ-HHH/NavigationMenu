package com.example.navigation_menu.service.chain.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.navigation_menu.mapper.BranchConfigMapper;
import com.example.navigation_menu.model.entity.BranchConfig;
import com.example.navigation_menu.model.vo.MenuTreeVO;
import com.example.navigation_menu.service.chain.MenuContext;
import com.example.navigation_menu.service.chain.MenuHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collections;

@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class LegacyCheckHandler implements MenuHandler {

    private final BranchConfigMapper branchConfigMapper;

    @Override
    public void handle(MenuContext context) {
        BranchConfig branch = branchConfigMapper.selectOne(
                new LambdaQueryWrapper<BranchConfig>()
                        .eq(BranchConfig::getBranchCode, context.getQuery().getBranchCode())
                        .eq(BranchConfig::getIsActive, 1)
        );

        if (branch != null && branch.getUseLegacyMenu() != null && branch.getUseLegacyMenu() == 1) {
            log.info("branch {} uses legacy menu, chain terminated", context.getQuery().getBranchCode());
            context.setResult(new MenuTreeVO(Collections.emptyList(), null));
            return;
        }

        log.info("branch {} uses new menu, continue chain", context.getQuery().getBranchCode());
    }
}