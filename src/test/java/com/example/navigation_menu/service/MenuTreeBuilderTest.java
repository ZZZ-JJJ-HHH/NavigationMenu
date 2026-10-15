package com.example.navigation_menu.service;

import com.example.navigation_menu.model.entity.MenuConfig;
import com.example.navigation_menu.model.vo.MenuNodeVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MenuTreeBuilderTest {

    private final MenuTreeBuilder treeBuilder = new MenuTreeBuilder();

    @Test
    @DisplayName("输入为 null 时返回空列表，不抛异常")
    void returnsEmptyListForNullInput() {
        assertThat(treeBuilder.build(null)).isEmpty();
    }

    @Test
    @DisplayName("输入为空列表时返回空列表")
    void returnsEmptyListForEmptyInput() {
        assertThat(treeBuilder.build(List.of())).isEmpty();
    }

    @Test
    @DisplayName("parentId 为 null 或 0 的节点都算根节点")
    void treatsNullAndZeroParentIdAsRoot() {
        List<MenuNodeVO> roots = treeBuilder.build(List.of(
                menu(1L, null, "home", 1),
                menu(2L, 0L, "account", 2)
        ));

        assertThat(roots).extracting(MenuNodeVO::getNodeId).containsExactly("home", "account");
    }

    @Test
    @DisplayName("子节点挂到父节点的 children 上，不会重复出现在根列表里")
    void nestsChildrenUnderParent() {
        List<MenuNodeVO> roots = treeBuilder.build(List.of(
                menu(1L, 0L, "transfer", 1),
                menu(10L, 1L, "transfer-inner", 1)
        ));

        assertThat(roots).hasSize(1);
        MenuNodeVO root = roots.get(0);
        assertThat(root.getNodeId()).isEqualTo("transfer");
        assertThat(root.getChildren()).extracting(MenuNodeVO::getNodeId).containsExactly("transfer-inner");
    }

    @Test
    @DisplayName("根节点按 sortOrder 升序排列，与入参顺序无关")
    void sortsRootsBySortOrder() {
        List<MenuNodeVO> roots = treeBuilder.build(List.of(
                menu(3L, 0L, "card", 3),
                menu(1L, 0L, "home", 1),
                menu(2L, 0L, "account", 2)
        ));

        assertThat(roots).extracting(MenuNodeVO::getNodeId).containsExactly("home", "account", "card");
    }

    @Test
    @DisplayName("同一父节点下的子节点也按 sortOrder 升序排列")
    void sortsChildrenBySortOrder() {
        List<MenuNodeVO> roots = treeBuilder.build(List.of(
                menu(1L, 0L, "transfer", 1),
                menu(11L, 1L, "transfer-global", 3),
                menu(10L, 1L, "transfer-inner", 1),
                menu(12L, 1L, "transfer-cross", 2)
        ));

        assertThat(roots.get(0).getChildren())
                .extracting(MenuNodeVO::getNodeId)
                .containsExactly("transfer-inner", "transfer-cross", "transfer-global");
    }

    @Test
    @DisplayName("sortOrder 为 null 时按 0 处理，排在最前")
    void treatsNullSortOrderAsZero() {
        List<MenuNodeVO> roots = treeBuilder.build(List.of(
                menu(1L, 0L, "second", 1),
                menu(2L, 0L, "first", null)
        ));

        assertThat(roots).extracting(MenuNodeVO::getNodeId).containsExactly("first", "second");
    }

    @Test
    @DisplayName("叶子节点的 children 保持为 null，而不是空列表")
    void leavesChildrenNullForLeafNode() {
        List<MenuNodeVO> roots = treeBuilder.build(List.of(menu(1L, 0L, "home", 1)));

        assertThat(roots.get(0).getChildren()).isNull();
    }

    @Test
    @DisplayName("parentId 指向不存在的节点时该节点被静默丢弃（当前实现的既有行为）")
    void dropsOrphanNodeSilently() {
        List<MenuNodeVO> roots = treeBuilder.build(List.of(
                menu(1L, 0L, "home", 1),
                menu(99L, 12345L, "orphan", 2)
        ));

        assertThat(roots).extracting(MenuNodeVO::getNodeId).containsExactly("home");
    }

    @Test
    @DisplayName("实体字段按预期拷贝到 VO")
    void copiesFieldsToVo() {
        MenuConfig config = menu(1L, 0L, "home", 1);
        config.setNodeName("首页");
        config.setNodePath("/home");
        config.setNodeIcon("home");

        MenuNodeVO node = treeBuilder.build(List.of(config)).get(0);

        assertThat(node.getNodeId()).isEqualTo("home");
        assertThat(node.getNodeName()).isEqualTo("首页");
        assertThat(node.getNodePath()).isEqualTo("/home");
        assertThat(node.getNodeIcon()).isEqualTo("home");
        assertThat(node.getSortOrder()).isEqualTo(1);
    }

    private MenuConfig menu(Long id, Long parentId, String nodeId, Integer sortOrder) {
        MenuConfig config = new MenuConfig();
        config.setId(id);
        config.setParentId(parentId);
        config.setNodeId(nodeId);
        config.setSortOrder(sortOrder);
        return config;
    }
}
