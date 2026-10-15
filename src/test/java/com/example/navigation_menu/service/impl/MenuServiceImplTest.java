package com.example.navigation_menu.service.impl;

import com.example.navigation_menu.model.dto.MenuQueryDTO;
import com.example.navigation_menu.model.vo.MenuTreeVO;
import com.example.navigation_menu.service.cache.MenuTreeCacheService;
import com.example.navigation_menu.service.chain.MenuContext;
import com.example.navigation_menu.service.chain.MenuHandlerChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MenuServiceImplTest {

    private static final String BRANCH = "SH001";
    private static final String USER = "normal_user";

    private final MenuTreeCacheService cacheService = mock(MenuTreeCacheService.class);
    private final MenuHandlerChain handlerChain = mock(MenuHandlerChain.class);
    private final MenuServiceImpl menuService = new MenuServiceImpl(cacheService, handlerChain);

    @Test
    @DisplayName("缓存命中时直接返回缓存结果，完全不进入责任链")
    void returnsCachedResultWithoutRunningChain() {
        MenuTreeVO cached = new MenuTreeVO(List.of(), null);
        when(cacheService.get(BRANCH, USER)).thenReturn(cached);

        MenuTreeVO result = menuService.buildMenuTree(query());


        assertThat(result).isSameAs(cached);
        verifyNoInteractions(handlerChain);
        verify(cacheService, never()).put(any(), any(), any());
    }

    @Test
    @DisplayName("缓存未命中时执行责任链，并把结果写回缓存")
    void runsChainOnCacheMissAndCachesResult() {
        MenuTreeVO built = new MenuTreeVO(List.of(), null);
        when(cacheService.get(BRANCH, USER)).thenReturn(null);
        doAnswer(invocation -> {
            invocation.getArgument(0, MenuContext.class).setResult(built);
            return null;
        }).when(handlerChain).proceed(any(MenuContext.class));

        MenuTreeVO result = menuService.buildMenuTree(query());

        assertThat(result).isSameAs(built);
        verify(cacheService).put(BRANCH, USER, built);
    }

    @Test
    @DisplayName("责任链没有产出结果时抛 IllegalStateException，并且不写缓存")
    void throwsWhenChainProducesNoResult() {
        // cacheService.get 未打桩，默认返回 null，即缓存未命中
        assertThatThrownBy(() -> menuService.buildMenuTree(query()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no result");

        verify(cacheService, never()).put(any(), any(), any());
    }

    @Test
    @DisplayName("责任链拿到的 context 里带着原始查询条件")
    void passesQueryIntoChainContext() {
        doAnswer(invocation -> {
            MenuContext context = invocation.getArgument(0, MenuContext.class);
            assertThat(context.getQuery().getBranchCode()).isEqualTo(BRANCH);
            assertThat(context.getQuery().getUserId()).isEqualTo(USER);
            context.setResult(new MenuTreeVO(List.of(), null));
            return null;
        }).when(handlerChain).proceed(any(MenuContext.class));

        menuService.buildMenuTree(query());

        verify(handlerChain).proceed(any(MenuContext.class));
    }

    private MenuQueryDTO query() {
        MenuQueryDTO dto = new MenuQueryDTO();
        dto.setCountryCode("CN");
        dto.setEntityCode("RETAIL");
        dto.setChannelCode("APP");
        dto.setBranchCode(BRANCH);
        dto.setUserId(USER);
        return dto;
    }
}
