package com.example.navigation_menu.service;

import com.example.navigation_menu.model.entity.MenuConfig;
import com.example.navigation_menu.model.vo.MenuNodeVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@Slf4j
public class MenuTreeBuilder {


    // 当前方案（两趟 childrenMap）
/*
    public List<MenuNodeVO> build(List<MenuConfig> menus) {
        if (menus == null || menus.isEmpty()) {
            return Collections.emptyList();
        }

        log.info("treeBuilder input: {} menus, ids={}",
                menus.size(),
                menus.stream().map(MenuConfig::getId).sorted().toList());

        // 按 sortOrder 升序排（null 当 0）
        List<MenuConfig> sorted = menus.stream()
                .sorted(Comparator.comparingInt(m -> m.getSortOrder() != null ? m.getSortOrder() : 0))
                .toList();

        // 第一步：按 parentId 分组，建一张"父节点 → 孩子列表"的索引表
        // key = 父节点 id，value = 这个父节点下面所有的孩子
        Map<Long, List<MenuNodeVO>> childrenMap = new LinkedHashMap<>();
        for (MenuConfig menu : sorted) {
            Long parentKey = menu.getParentId();
            if (parentKey == null) {
                parentKey = 0L;
            }

            List<MenuNodeVO> children = childrenMap.get(parentKey);
            if (children == null) {
                children = new ArrayList<>();
                childrenMap.put(parentKey, children);
            }

            children.add(convert(menu));
        }

        // 第二步：用索引表把子节点挂到父节点的 children 字段上

        // 遍历所有的节点，判断该节点是否还有子节点（判断该节点是否是其他节点的父节点，查询 childrenMap 是否有该节点的 id）。如果有，把子节点挂到该节点的 children 字段上，否则，设置为 null。因为每个节点都遍历到了，所以不会漏掉任何节点。
        for (Map.Entry<Long, List<MenuNodeVO>> entry : childrenMap.entrySet()) {
            List<MenuNodeVO> children = entry.getValue();


            for (MenuNodeVO child : children) {
                Long childMenuId = findMenuId(sorted, child.getNodeId());
                if (childMenuId != null && childrenMap.containsKey(childMenuId)) {
                    child.setChildren(childrenMap.get(childMenuId));
                }
            }
        }

        // 第三步：返回根节点
        List<MenuNodeVO> roots = childrenMap.getOrDefault(0L, new ArrayList<>());
        log.info("tree built, roots={}", roots.size());
        return roots;
    }
*/


    // 方案二（单趟 idMap）
    public List<MenuNodeVO> build(List<MenuConfig> menus) {
        log.debug("treeBuilder input: {} menus", menus == null ? 0 : menus.size());
        if (menus == null || menus.isEmpty()) {
            return Collections.emptyList();
        }

        List<MenuConfig> sorted = menus.stream()
                .sorted(Comparator.comparingInt(m -> m.getSortOrder() != null ? m.getSortOrder() : 0))
                .toList();

        Map<Long, MenuNodeVO> idMap = new LinkedHashMap<>();
        for (MenuConfig menu : sorted) {
            idMap.put(menu.getId(), convert(menu));
        }
        log.debug("idMap built, size={}, keys={}", idMap.size(), idMap.keySet());

        List<MenuNodeVO> roots = new ArrayList<>();
        for (MenuConfig menu : sorted) {
            MenuNodeVO node = idMap.get(menu.getId());
            Long parentId = menu.getParentId();

            if (parentId == null || parentId == 0L) {
                roots.add(node);
            } else {
                MenuNodeVO parent = idMap.get(parentId);
                log.debug("menu[id={}] parentId={}, parent found={}", menu.getId(), parentId, parent != null);
                if (parent != null) {
                    if (parent.getChildren() == null) {
                        parent.setChildren(new ArrayList<>());
                    }
                    parent.getChildren().add(node);
                }
            }
        }

        log.debug("tree built, roots={}", roots.size());
        return roots;
    }


    private MenuNodeVO convert(MenuConfig menu) {
        MenuNodeVO vo = new MenuNodeVO();
        vo.setNodeId(menu.getNodeId());
        vo.setNodeName(menu.getNodeName());
        vo.setNodePath(menu.getNodePath());
        vo.setNodeIcon(menu.getNodeIcon());
        vo.setSortOrder(menu.getSortOrder());
        return vo;
    }
}
