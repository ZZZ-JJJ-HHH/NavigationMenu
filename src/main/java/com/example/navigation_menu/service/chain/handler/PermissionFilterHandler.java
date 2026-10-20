package com.example.navigation_menu.service.chain.handler;

import com.example.navigation_menu.service.PermissionFilter;
import com.example.navigation_menu.model.entity.MenuConfig;
import com.example.navigation_menu.service.chain.MenuContext;
import com.example.navigation_menu.service.chain.MenuHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.logstash.logback.argument.StructuredArguments;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Order(4)
@RequiredArgsConstructor
@Slf4j
public class PermissionFilterHandler implements MenuHandler {

    private final PermissionFilter permissionFilter;

    @Override
    public void handle(MenuContext context) {
        Set<String> userPerms = mockUserPermissions(context.getQuery().getUserId());
        context.setUserPerms(userPerms);

        List<MenuConfig> matched = context.getMatched() == null ? List.of() : context.getMatched();
        var filtered = permissionFilter.filter(matched, userPerms);
        context.setFiltered(filtered);

        Set<String> keptIds = filtered.stream().map(MenuConfig::getNodeId).collect(Collectors.toSet());
        List<String> cutNodeIds = matched.stream()
                .map(MenuConfig::getNodeId)
                .filter(nodeId -> !keptIds.contains(nodeId))
                .toList();

        log.info("after permission filter: {} nodes", filtered.size());
        log.debug("permission filter: {} -> {}, cut={}",
                matched.size(), filtered.size(), cutNodeIds.size(),
                StructuredArguments.kv("eventType", "permission.filter"),
                StructuredArguments.kv("before", matched.size()),
                StructuredArguments.kv("after", filtered.size()),
                StructuredArguments.kv("cut", cutNodeIds.size()),
                StructuredArguments.kv("cutNodeIds", cutNodeIds),
                StructuredArguments.kv("userPerms", userPerms));
    }

    private Set<String> mockUserPermissions(String userId) {
        if (userId == null) {
            return Collections.emptySet();
        }
        if ("luxury_user".equals(userId)) {
            return Set.of("LUXURY_CARD");
        }
        return Collections.emptySet();
    }
}
