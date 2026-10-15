package com.example.navigation_menu.service.chain.handler;

import com.example.navigation_menu.mapper.BranchConfigMapper;
import com.example.navigation_menu.model.dto.MenuQueryDTO;
import com.example.navigation_menu.model.entity.BranchConfig;
import com.example.navigation_menu.service.chain.MenuContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LegacyCheckHandlerTest {

    private final BranchConfigMapper branchConfigMapper = mock(BranchConfigMapper.class);
    private final LegacyCheckHandler handler = new LegacyCheckHandler(branchConfigMapper);

    @Test
    @DisplayName("分行开关指向老系统时短路，直接产出空菜单树")
    void terminatesChainForLegacyBranch() {
        when(branchConfigMapper.selectOne(any())).thenReturn(branch(1));
        MenuContext context = contextFor("HK001");

        handler.handle(context);

        assertThat(context.getResult()).isNotNull();
        assertThat(context.getResult().getMenuTree()).isEmpty();
    }

    @Test
    @DisplayName("分行走新平台时不产出结果，责任链继续往下执行")
    void continuesForModernBranch() {
        when(branchConfigMapper.selectOne(any())).thenReturn(branch(0));
        MenuContext context = contextFor("SH001");

        handler.handle(context);

        assertThat(context.getResult()).isNull();
    }

    @Test
    @DisplayName("查不到分行配置时按新平台处理，不短路")
    void continuesWhenBranchNotFound() {
        when(branchConfigMapper.selectOne(any())).thenReturn(null);
        MenuContext context = contextFor("UNKNOWN");

        handler.handle(context);

        assertThat(context.getResult()).isNull();
    }

    @Test
    @DisplayName("开关字段为 null 时按新平台处理，不短路")
    void continuesWhenLegacyFlagIsNull() {
        BranchConfig config = new BranchConfig();
        config.setBranchCode("SH001");
        config.setUseLegacyMenu(null);
        when(branchConfigMapper.selectOne(any())).thenReturn(config);
        MenuContext context = contextFor("SH001");

        handler.handle(context);

        assertThat(context.getResult()).isNull();
    }

    private BranchConfig branch(Integer useLegacyMenu) {
        BranchConfig config = new BranchConfig();
        config.setBranchCode("SH001");
        config.setUseLegacyMenu(useLegacyMenu);
        return config;
    }

    private MenuContext contextFor(String branchCode) {
        MenuQueryDTO dto = new MenuQueryDTO();
        dto.setBranchCode(branchCode);
        MenuContext context = new MenuContext();
        context.setQuery(dto);
        return context;
    }
}
