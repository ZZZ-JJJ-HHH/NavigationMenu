package com.example.navigation_menu.service;

import com.example.navigation_menu.model.entity.MenuConfig;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class MenuFallbackService {

    public List<MenuConfig> match(List<MenuConfig> allMenus,
                                  String country, String entity, String channel, String branch) {

        List<MenuConfig> matched = new ArrayList<>();

        for (MenuConfig menu : allMenus) {
            if (wildcardMatch(menu.getCountryCode(), country)
                    && wildcardMatch(menu.getEntityCode(), entity)
                    && wildcardMatch(menu.getChannelCode(), channel)
                    && wildcardMatch(menu.getBranchCode(), branch)) {
                matched.add(menu);
            }
        }

        return deduplicateByPrecision(matched);
    }

    private boolean wildcardMatch(String pattern, String value) {
        // 通配符匹配
        if ("*".equals(pattern)) {
            return true;
        }
        // 普通匹配
        return pattern != null && pattern.equals(value);
    }

    private List<MenuConfig> deduplicateByPrecision(List<MenuConfig> matched) {
        Map<String, MenuConfig> dedupMap = matched.stream()
                .collect(Collectors.toMap(
                        MenuConfig::getNodeId,
                        menu -> menu,
                        (existing, incoming) -> {
                            int incomingScore = calcPrecisionScore(incoming);
                            int existingScore = calcPrecisionScore(existing);
                            return incomingScore > existingScore ? incoming : existing;
                        }
                ));

        return new ArrayList<>(dedupMap.values());
    }

    private int calcPrecisionScore(MenuConfig menu) {
        int score = 0;
        if (!"*".equals(menu.getCountryCode())) score++;
        if (!"*".equals(menu.getEntityCode())) score++;
        if (!"*".equals(menu.getChannelCode())) score++;
        if (!"*".equals(menu.getBranchCode())) score++;
        return score;
    }
}