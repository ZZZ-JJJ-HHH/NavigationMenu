package com.example.navigation_menu.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.example.navigation_menu.mapper")
public class MybatisPlusConfig {
}