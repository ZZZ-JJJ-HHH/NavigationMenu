package com.example.navigation_menu.service.chain.handler;

import com.example.navigation_menu.service.MenuFallbackService;
import com.example.navigation_menu.service.chain.MenuContext;
import com.example.navigation_menu.service.chain.MenuHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(3)
@RequiredArgsConstructor
@Slf4j
public class FallbackMatchHandler implements MenuHandler {

    private final MenuFallbackService fallbackService;

    @Override
    public void handle(MenuContext context) {
        var matched = fallbackService.match(
                context.getAllMenus(),
                context.getQuery().getCountryCode(),
                context.getQuery().getEntityCode(),
                context.getQuery().getChannelCode(),
                context.getQuery().getBranchCode()
        );
        context.setMatched(matched);
        log.info("after fallback match: {} nodes", matched.size());
    }
}