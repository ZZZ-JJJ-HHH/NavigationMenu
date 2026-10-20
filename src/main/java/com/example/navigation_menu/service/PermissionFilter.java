package com.example.navigation_menu.service;

import com.example.navigation_menu.model.entity.MenuConfig;
import lombok.extern.slf4j.Slf4j;
import net.logstash.logback.argument.StructuredArguments;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class PermissionFilter {

    public List<MenuConfig> filter(List<MenuConfig> matched, Set<String> userPermissions) {
        if (matched == null || matched.isEmpty()) {
            return Collections.emptyList();
        }

        return matched.stream()
                .filter(menu -> {
                    boolean allowed = hasPermission(menu, userPermissions);
                    if (!allowed) {
                        // 每砍掉一个节点记一条：为什么砍的，缺的是哪个权限。
                        // DEBUG —— 条数跟着数据量走（每被拒的节点一条）。
                        log.debug("permission denied: nodeId={}, requiredPerm={}",
                                menu.getNodeId(), menu.getRequiredPerm(),
                                StructuredArguments.kv("eventType", "permission.filter"),
                                StructuredArguments.kv("decision", "deny"),
                                StructuredArguments.kv("nodeId", menu.getNodeId()),
                                StructuredArguments.kv("requiredPerm", menu.getRequiredPerm()));
                    }
                    return allowed;
                })
                .collect(Collectors.toList());
    }

    private boolean hasPermission(MenuConfig menu, Set<String> userPermissions) {
        if (menu.getRequiredPerm() == null || menu.getRequiredPerm().isBlank()) {
            return true;
        }
        return userPermissions != null && userPermissions.contains(menu.getRequiredPerm());
    }
}
