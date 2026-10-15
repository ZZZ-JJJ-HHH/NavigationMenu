INSERT INTO menu_config (country_code, entity_code, channel_code, branch_code, parent_id, node_id, node_name, node_path, node_icon, sort_order, required_perm) VALUES
('*', '*', '*', '*', 0, 'home', '首页', '/home', 'home', 1, NULL),
('*', '*', '*', '*', 0, 'account', '我的账户', '/account', 'account', 2, NULL),
('*', '*', '*', '*', 0, 'transfer', '转账汇款', '/transfer', 'transfer', 3, NULL),
('*', '*', '*', '*', 0, 'finance', '理财投资', '/finance', 'finance', 4, NULL),
('*', '*', '*', '*', 0, 'card', '信用卡', '/card', 'card', 5, NULL),
('*', '*', '*', '*', 0, 'loan', '贷款', '/loan', 'loan', 6, NULL),
('*', '*', '*', '*', 0, 'mine', '我的', '/mine', 'mine', 99, NULL),

('HK', '*', '*', '*', 0, 'account', '我的财富', '/account', 'account', 2, NULL),

('*', '*', '*', '*', 3, 'transfer-inner', '行内转账', '/transfer/inner', NULL, 1, NULL),
('*', '*', '*', '*', 3, 'transfer-cross', '跨行转账', '/transfer/cross', NULL, 2, NULL),
('*', '*', '*', '*', 3, 'transfer-global', '跨境汇款', '/transfer/global', NULL, 3, NULL),

('*', '*', '*', '*', 4, 'fund', '基金', '/finance/fund', NULL, 1, NULL),
('*', '*', '*', '*', 4, 'product', '理财产品', '/finance/product', NULL, 2, NULL),
('*', '*', '*', '*', 4, 'stock', '股票', '/finance/stock', NULL, 3, NULL),

('*', '*', '*', '*', 5, 'card-my', '我的卡', '/card/my', NULL, 1, NULL),
('*', '*', '*', '*', 5, 'card-bill', '账单查询', '/card/bill', NULL, 2, NULL),
('*', '*', '*', '*', 5, 'card-point', '积分商城', '/card/point', NULL, 3, NULL),

('*', '*', '*', '*', 6, 'loan-house', '房贷', '/loan/house', NULL, 1, NULL),
('*', '*', '*', '*', 6, 'loan-consume', '消费贷', '/loan/consume', NULL, 2, NULL),
('*', '*', '*', '*', 6, 'loan-biz', '经营贷', '/loan/biz', NULL, 3, NULL),

('*', '*', '*', '*', 7, 'mine-profile', '个人信息', '/mine/profile', NULL, 1, NULL),
('*', '*', '*', '*', 7, 'mine-security', '安全设置', '/mine/security', NULL, 2, NULL),
('*', '*', '*', '*', 7, 'mine-help', '帮助中心', '/mine/help', NULL, 3, NULL),

('CN', 'RETAIL', 'APP', 'SH001', 2, 'digital-rmb', '数字人民币', '/account/digital-rmb', NULL, 1, NULL),
('*', '*', '*', '*', 0, 'luxury', '黑卡专区', '/luxury', NULL, 50, 'LUXURY_CARD');