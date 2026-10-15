package com.example.navigation_menu.service;

import com.example.navigation_menu.model.entity.MenuConfig;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class PermissionFilter {

    public List<MenuConfig> filter(List<MenuConfig> matched, Set<String> userPermissions) {
        if (matched == null || matched.isEmpty()) {
            return Collections.emptyList();
        }

        return matched.stream()
                .filter(menu -> hasPermission(menu, userPermissions))
                .collect(Collectors.toList());
    }

    private boolean hasPermission(MenuConfig menu, Set<String> userPermissions) {
        if (menu.getRequiredPerm() == null || menu.getRequiredPerm().isBlank()) {
            return true;
        }
        return userPermissions != null && userPermissions.contains(menu.getRequiredPerm());
    }
}