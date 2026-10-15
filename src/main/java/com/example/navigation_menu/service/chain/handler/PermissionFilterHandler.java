package com.example.navigation_menu.service.chain.handler;

import com.example.navigation_menu.service.PermissionFilter;
import com.example.navigation_menu.service.chain.MenuContext;
import com.example.navigation_menu.service.chain.MenuHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;

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

        var filtered = permissionFilter.filter(context.getMatched(), userPerms);
        context.setFiltered(filtered);
        log.info("after permission filter: {} nodes", filtered.size());
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