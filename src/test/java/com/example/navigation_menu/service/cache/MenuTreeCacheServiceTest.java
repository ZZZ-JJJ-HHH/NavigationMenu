package com.example.navigation_menu.service.cache;

import com.example.navigation_menu.model.vo.MenuTreeVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MenuTreeCacheServiceTest {

    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);

    private final MenuTreeCacheService cacheService = new MenuTreeCacheService(redisTemplate);

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("put 使用 menu:tree:{分行}:{用户} 作为 key，并带 10 分钟 TTL")
    void putUsesExpectedKeyAndTtl() {
        MenuTreeVO tree = new MenuTreeVO(List.of(), null);

        cacheService.put("SH001", "normal_user", tree);

        verify(valueOperations).set("menu:tree:SH001:normal_user", tree, Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("userId 为 null 时 key 用 anonymous 兜底，不会写出 null 字面量")
    void putFallsBackToAnonymousWhenUserIdIsNull() {
        MenuTreeVO tree = new MenuTreeVO(List.of(), null);

        cacheService.put("HK001", null, tree);

        verify(valueOperations).set("menu:tree:HK001:anonymous", tree, Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("get 命中时返回缓存中的菜单树")
    void getReturnsCachedValue() {
        MenuTreeVO tree = new MenuTreeVO(List.of(), null);
        when(valueOperations.get("menu:tree:SH001:normal_user")).thenReturn(tree);

        assertThat(cacheService.get("SH001", "normal_user")).isSameAs(tree);
    }

    @Test
    @DisplayName("get 未命中时返回 null")
    void getReturnsNullOnMiss() {
        assertThat(cacheService.get("SH001", "normal_user")).isNull();
    }

    @Test
    @DisplayName("缓存里存的类型不对时按未命中处理，不抛 ClassCastException")
    void getReturnsNullForUnexpectedType() {
        when(valueOperations.get("menu:tree:SH001:normal_user")).thenReturn("不是菜单树");

        assertThat(cacheService.get("SH001", "normal_user")).isNull();
    }

    @Test
    @DisplayName("evict 删除对应 key")
    void evictDeletesKey() {
        cacheService.evict("SH001", "normal_user");

        verify(redisTemplate).delete("menu:tree:SH001:normal_user");
    }
}
