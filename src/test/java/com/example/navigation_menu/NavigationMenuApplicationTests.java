package com.example.navigation_menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 启动冒烟测试：验证 Spring 容器能把所有 Bean 装配起来。
 * 会真实连接 MySQL 和 Redis，属于集成测试，运行前需保证依赖已就绪。
 */
@SpringBootTest
class NavigationMenuApplicationTests {

    @Test
    @DisplayName("Spring 容器能正常启动")
    void contextLoads() {
    }
}
