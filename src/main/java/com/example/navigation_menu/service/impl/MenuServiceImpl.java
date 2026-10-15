package com.example.navigation_menu.service.impl;

import com.example.navigation_menu.model.dto.MenuQueryDTO;
import com.example.navigation_menu.model.vo.MenuTreeVO;
import com.example.navigation_menu.service.MenuService;
import com.example.navigation_menu.service.cache.MenuTreeCacheService;
import com.example.navigation_menu.service.chain.MenuContext;
import com.example.navigation_menu.service.chain.MenuHandlerChain;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class MenuServiceImpl implements MenuService {

    private final MenuTreeCacheService cacheService;
    private final MenuHandlerChain handlerChain;

    @Override
    public MenuTreeVO buildMenuTree(MenuQueryDTO dto) {
        log.info("buildMenuTree start, query: {}", dto);

        String branchCode = dto.getBranchCode();
        String userId = dto.getUserId();

        // 从缓存中获取
        MenuTreeVO cached = cacheService.get(branchCode, userId);
        if (cached != null) {
            return cached;
        }

        MenuContext context = new MenuContext();
        context.setQuery(dto);
        handlerChain.proceed(context);

        MenuTreeVO result = context.getResult();
        if (result == null) {
            throw new IllegalStateException("menu handler chain produced no result");
        }

        cacheService.put(branchCode, userId, result);
        return result;
    }
}