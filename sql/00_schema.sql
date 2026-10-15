-- 建库建表脚本。文件名以 00 开头，保证在 init_branch.sql / init_data.sql 之前执行。
-- 既用于 docker-entrypoint-initdb.d 自动初始化，也可手工执行：mysql -u root -p < sql/00_schema.sql

CREATE DATABASE IF NOT EXISTS navigation_menu
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE navigation_menu;

-- 表 1：菜单配置主表（核心）
CREATE TABLE IF NOT EXISTS menu_config (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,

    country_code    VARCHAR(10)  NOT NULL DEFAULT '*',
    entity_code     VARCHAR(20)  NOT NULL DEFAULT '*',
    channel_code    VARCHAR(20)  NOT NULL DEFAULT '*',
    branch_code     VARCHAR(20)  NOT NULL DEFAULT '*',

    parent_id       BIGINT       DEFAULT 0,
    node_id         VARCHAR(50)  NOT NULL,
    node_name       VARCHAR(100) NOT NULL,
    node_path       VARCHAR(200),
    node_icon       VARCHAR(50),
    sort_order      INT          DEFAULT 0,

    required_perm   VARCHAR(50),
    is_active       TINYINT      DEFAULT 1,
    create_by       VARCHAR(50),
    create_time     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_by       VARCHAR(50),
    update_time     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted      TINYINT      DEFAULT 0,

    INDEX idx_match (country_code, entity_code, channel_code, branch_code),
    INDEX idx_parent (parent_id),
    INDEX idx_node (node_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 表 2：分行配置表（Legacy 开关）
CREATE TABLE IF NOT EXISTS branch_config (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    branch_code     VARCHAR(20)  NOT NULL UNIQUE,
    branch_name     VARCHAR(100),
    country_code    VARCHAR(10),
    use_legacy_menu TINYINT      DEFAULT 0,
    is_active       TINYINT      DEFAULT 1,
    create_time     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
