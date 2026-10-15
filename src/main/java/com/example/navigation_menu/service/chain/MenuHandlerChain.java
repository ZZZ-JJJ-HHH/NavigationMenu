package com.example.navigation_menu.service.chain;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
@Slf4j
public class MenuHandlerChain {

    private final List<MenuHandler> sortedHandlers;

    public MenuHandlerChain(List<MenuHandler> handlers) {
        this.sortedHandlers = handlers.stream()
                .sorted(Comparator.comparingInt(h -> {
                    Order order = h.getClass().getAnnotation(Order.class);
                    return order != null ? order.value() : Integer.MAX_VALUE;
                }))
                .toList();
    }

    public void proceed(MenuContext context) {
        for (MenuHandler handler : sortedHandlers) {
            log.info("chain step: {}", handler.getClass().getSimpleName());
            handler.handle(context);
            if (context.getResult() != null) {
                log.info("chain terminated at {}", handler.getClass().getSimpleName());
                return;
            }
        }
    }
}