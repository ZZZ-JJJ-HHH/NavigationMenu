package com.example.navigation_menu.service;

import com.example.navigation_menu.model.entity.MenuConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionFilterTest {

    private static final String LUXURY_CARD = "LUXURY_CARD";

    private final PermissionFilter permissionFilter = new PermissionFilter();

    @Test
    @DisplayName("入参为 null 时返回空列表，不抛异常")
    void returnsEmptyListForNullInput() {
        assertThat(permissionFilter.filter(null, Set.of(LUXURY_CARD))).isEmpty();
    }

    @Test
    @DisplayName("入参为空列表时返回空列表")
    void returnsEmptyListForEmptyInput() {
        assertThat(permissionFilter.filter(List.of(), Set.of(LUXURY_CARD))).isEmpty();
    }

    @Test
    @DisplayName("requiredPerm 为 null 的菜单对所有人可见")
    void keepsMenuWithoutRequiredPermission() {
        List<MenuConfig> result = permissionFilter.filter(List.of(menu("home", null)), Set.of());

        assertThat(result).extracting(MenuConfig::getNodeId).containsExactly("home");
    }

    @Test
    @DisplayName("requiredPerm 为空白字符串时同样视为无权限要求")
    void keepsMenuWithBlankRequiredPermission() {
        List<MenuConfig> result = permissionFilter.filter(List.of(menu("home", "   ")), Set.of());

        assertThat(result).extracting(MenuConfig::getNodeId).containsExactly("home");
    }

    @Test
    @DisplayName("用户持有对应权限时菜单保留")
    void keepsMenuWhenUserHasPermission() {
        List<MenuConfig> result = permissionFilter.filter(
                List.of(menu("card-luxury", LUXURY_CARD)),
                Set.of(LUXURY_CARD)
        );

        assertThat(result).extracting(MenuConfig::getNodeId).containsExactly("card-luxury");
    }

    @Test
    @DisplayName("用户缺少对应权限时菜单被过滤掉")
    void filtersOutMenuWhenUserLacksPermission() {
        List<MenuConfig> result = permissionFilter.filter(
                List.of(menu("card-luxury", LUXURY_CARD)),
                Set.of("SOME_OTHER_PERM")
        );

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("用户权限集合为 null 时，只保留不需要权限的菜单")
    void keepsOnlyPermissionFreeMenusWhenUserPermissionsIsNull() {
        List<MenuConfig> result = permissionFilter.filter(
                List.of(menu("home", null), menu("card-luxury", LUXURY_CARD)),
                null
        );

        assertThat(result).extracting(MenuConfig::getNodeId).containsExactly("home");
    }

    private MenuConfig menu(String nodeId, String requiredPerm) {
        MenuConfig config = new MenuConfig();
        config.setNodeId(nodeId);
        config.setRequiredPerm(requiredPerm);
        return config;
    }
}
