package com.example.navigation_menu.service;

import com.example.navigation_menu.model.entity.MenuConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MenuFallbackServiceTest {

    private static final String CN = "CN";
    private static final String HK = "HK";
    private static final String RETAIL = "RETAIL";
    private static final String APP = "APP";
    private static final String SH001 = "SH001";

    private final MenuFallbackService fallbackService = new MenuFallbackService();

    @Test
    @DisplayName("四维全通配的菜单能匹配任意查询组合")
    void matchesFullyWildcardMenu() {
        List<MenuConfig> all = List.of(menu("home", "*", "*", "*", "*"));

        List<MenuConfig> matched = fallbackService.match(all, CN, RETAIL, APP, SH001);

        assertThat(matched).extracting(MenuConfig::getNodeId).containsExactly("home");
    }

    @Test
    @DisplayName("任意一维不匹配的菜单都会被排除")
    void excludesMenuWhenAnyDimensionDiffers() {
        List<MenuConfig> all = List.of(menu("hk-only", HK, "*", "*", "*"));

        List<MenuConfig> matched = fallbackService.match(all, CN, RETAIL, APP, SH001);

        assertThat(matched).isEmpty();
    }

    @Test
    @DisplayName("同一 nodeId 命中多条时，保留精确度更高（通配符更少）的那条")
    void keepsMoreSpecificMenuForSameNodeId() {
        List<MenuConfig> all = List.of(
                menu("home", "*", "*", "*", "*"),
                menu("home", CN, "*", "*", SH001)
        );

        List<MenuConfig> matched = fallbackService.match(all, CN, RETAIL, APP, SH001);

        assertThat(matched).hasSize(1);
        assertThat(matched.get(0).getCountryCode()).isEqualTo(CN);
        assertThat(matched.get(0).getBranchCode()).isEqualTo(SH001);
    }

    @Test
    @DisplayName("通配度相同时保留其中一条，不会产生重复 nodeId")
    void keepsSingleEntryWhenPrecisionTies() {
        List<MenuConfig> all = List.of(
                menu("home", CN, "*", "*", "*"),
                menu("home", CN, "*", "*", "*")
        );

        List<MenuConfig> matched = fallbackService.match(all, CN, RETAIL, APP, SH001);

        assertThat(matched).hasSize(1);
    }

    @Test
    @DisplayName("不同 nodeId 的匹配结果全部保留")
    void keepsMenusWithDistinctNodeIds() {
        List<MenuConfig> all = List.of(
                menu("home", "*", "*", "*", "*"),
                menu("account", CN, "*", "*", "*")
        );

        List<MenuConfig> matched = fallbackService.match(all, CN, RETAIL, APP, SH001);

        assertThat(matched).extracting(MenuConfig::getNodeId).containsExactlyInAnyOrder("home", "account");
    }

    @Test
    @DisplayName("没有任何菜单匹配时返回空列表")
    void returnsEmptyWhenNothingMatches() {
        assertThat(fallbackService.match(List.of(), CN, RETAIL, APP, SH001)).isEmpty();
    }

    private MenuConfig menu(String nodeId, String country, String entity, String channel, String branch) {
        MenuConfig config = new MenuConfig();
        config.setNodeId(nodeId);
        config.setCountryCode(country);
        config.setEntityCode(entity);
        config.setChannelCode(channel);
        config.setBranchCode(branch);
        return config;
    }
}
