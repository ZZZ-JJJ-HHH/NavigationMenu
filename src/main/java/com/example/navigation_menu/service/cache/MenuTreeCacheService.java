package com.example.navigation_menu.service.cache;

import com.example.navigation_menu.model.vo.MenuTreeVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class MenuTreeCacheService {

    private static final String KEY_PREFIX = "menu:tree:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final RedisTemplate<String, Object> redisTemplate;

    public MenuTreeVO get(String branchCode, String userId) {
        String key = buildKey(branchCode, userId);
        Object cached = redisTemplate.opsForValue().get(key);
        if (cached instanceof MenuTreeVO vo) {
            log.info("cache hit, key={}", key);
            return vo;
        }
        log.info("cache miss, key={}", key);
        return null;
    }

    public void put(String branchCode, String userId, MenuTreeVO tree) {
        String key = buildKey(branchCode, userId);
        redisTemplate.opsForValue().set(key, tree, CACHE_TTL);
        log.info("cache set, key={}, ttl={}", key, CACHE_TTL);
    }

    public void evict(String branchCode, String userId) {
        String key = buildKey(branchCode, userId);
        redisTemplate.delete(key);
        log.info("cache evicted, key={}", key);
    }

    private String buildKey(String branchCode, String userId) {
        String userKey = userId != null ? userId : "anonymous";
        return KEY_PREFIX + branchCode + ":" + userKey;
    }
}