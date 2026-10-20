package com.example.navigation_menu.service;

import com.example.navigation_menu.model.entity.MenuConfig;
import lombok.extern.slf4j.Slf4j;
import net.logstash.logback.argument.StructuredArguments;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
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

        List<MenuConfig> deduped = deduplicateByPrecision(matched);

        // 决策日志：候选多少条、去重后剩多少条、精确/通配各几条、具体是哪些 nodeId。
        // 级别是 DEBUG —— 条数跟着数据量走（每条菜单都会参与匹配），生产默认关掉。
        int exact = (int) deduped.stream().filter(menu -> calcPrecisionScore(menu) == 4).count();
        log.debug("fallback match: {} candidates -> {} nodes, exact={}, wildcard={}",
                matched.size(), deduped.size(), exact, deduped.size() - exact,
                StructuredArguments.kv("eventType", "fallback.match"),
                StructuredArguments.kv("candidates", matched.size()),
                StructuredArguments.kv("matched", deduped.size()),
                StructuredArguments.kv("exact", exact),
                StructuredArguments.kv("wildcard", deduped.size() - exact),
                StructuredArguments.kv("nodeIds",
                        deduped.stream().map(MenuConfig::getNodeId).toList()));

        return deduped;
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
