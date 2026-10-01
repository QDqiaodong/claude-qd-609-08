-- 射箭馆（弓箭俱乐部）演示数据：建表 + 初始化数据
-- 回合的箭支走 round_arrow 这张有序集合连接表，shot_index 从 0 连续递增。

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS lanes (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  lane_no       VARCHAR(16)  NOT NULL COMMENT '道号',
  distance      INT          NOT NULL COMMENT '距离（米）：10/18/30/50',
  target_type   VARCHAR(32)  NOT NULL COMMENT '箭靶类型',
  status        VARCHAR(16)  NOT NULL COMMENT 'OPEN 开放 / OCCUPIED 占用 / MAINTENANCE 维护 / LOCKED 安全锁定',
  hourly_price  DECIMAL(10,2) NOT NULL COMMENT '每小时单价',
  occupant_id   BIGINT       NULL COMMENT '占用会员',
  occupant_name VARCHAR(32)  NULL,
  opened_at     DATETIME     NULL COMMENT '开台时间',
  planned_hours INT          NULL COMMENT '预计时长（小时）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_lane_no (lane_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS members (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  card_no       VARCHAR(24)  NOT NULL COMMENT '会员卡号（封存档案保留原卡号可查）',
  name          VARCHAR(32)  NOT NULL,
  phone         VARCHAR(20)  NOT NULL,
  level         VARCHAR(16)  NOT NULL COMMENT 'NORMAL 普通 / SILVER 银 / GOLD 金',
  balance       DECIMAL(10,2) NOT NULL COMMENT '余额',
  register_date DATE         NOT NULL COMMENT '注册日期',
  total_spend   DECIMAL(12,2) NOT NULL COMMENT '累计消费',
  status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 在用 / MERGED 已合并封存（历史档案，不可继续使用）',
  merged_into_id BIGINT      NULL COMMENT '封存时合并去向的保留会员 id（不加外键，避免与合并流程相互锁定）',
  merged_at     DATETIME     NULL COMMENT '合并封存时间',
  merge_reason  VARCHAR(500) NULL COMMENT '合并原因（值班经理填写）',
  merged_by     VARCHAR(32)  NULL COMMENT '执行合并的值班经理',
  minor_flag    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '未成年会员标记：1 未成年（课程 / 箭道 / 器材均受监护授权台约束）/ 0 成年（流程不变）',
  birth_date    DATE         NULL COMMENT '出生日期（未成年会员登记，供核验年龄）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_card_no (card_no),
  UNIQUE KEY uk_phone (phone),
  KEY idx_members_status (status),
  KEY idx_members_merged_into (merged_into_id),
  KEY idx_members_minor (minor_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ================= 模块九：重复会员档案合并 =================
-- 器材租借影子记录：同一件器材在历史误操作中分别挂在两张会员卡名下的异常登记。
-- 正常业务（租借 / 归还）不写本表；合并预演用它与正式租约取交集，识别「双方租出同一件器材」的互斥。
CREATE TABLE IF NOT EXISTS merge_equipment_rent_shadow (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  equipment_id BIGINT       NOT NULL,
  equip_code   VARCHAR(24)  NOT NULL,
  type         VARCHAR(16)  NOT NULL,
  brand        VARCHAR(32)  NOT NULL,
  renter_id    BIGINT       NOT NULL,
  rented_at    DATETIME     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_merge_equip_shadow_renter (renter_id),
  KEY idx_merge_equip_shadow_equip (equipment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 合并预演：值班经理选定保留档案 + 来源档案后生成，确认必须以预演版本为依据。
-- 状态机 PREVIEWED 待确认 → CONSUMED 已用于成功合并（一次性）；
-- 任一端确认成功即原子转为 CONSUMED，另一端凭同一版本确认会被拒绝并看到去向。
CREATE TABLE IF NOT EXISTS member_merge_preview (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  preview_no       VARCHAR(24)  NOT NULL COMMENT '预演版本号',
  keep_member_id   BIGINT       NOT NULL COMMENT '保留档案会员',
  source_member_id BIGINT       NOT NULL COMMENT '来源档案会员（合并后封存）',
  operator         VARCHAR(32)  NOT NULL COMMENT '发起预演的值班经理',
  reason           VARCHAR(500) NOT NULL COMMENT '合并原因',
  status           VARCHAR(16)  NOT NULL COMMENT 'PREVIEWED 待确认 / CONSUMED 已用于合并 / SUPERSEDED 已被新预演取代',
  -- 预演时刻两侧业务数据指纹：确认时重算比对，余额 / 报名 / 回合 / 租借 / 认证任一变化即拒绝
  keep_fingerprint   VARCHAR(64) NOT NULL,
  source_fingerprint VARCHAR(64) NOT NULL,
  -- 预演结论快照（互斥项 / 去重项 / 合并后金额等级），确认时回显，不落空
  snapshot_json    TEXT         NOT NULL,
  created_at       DATETIME     NOT NULL,
  consumed_at      DATETIME     NULL,
  consumed_by      VARCHAR(32)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_merge_preview_no (preview_no),
  KEY idx_merge_preview_source (source_member_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 合并轨迹：成功合并一条，记录操作者、原因、两侧原卡号 / 原姓名 / 合并前余额与累计消费、
-- 迁移与去重明细，供会员列表 / 详情页与会员本人查询（只能查看，不能撤销）。
CREATE TABLE IF NOT EXISTS member_merge_log (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  merge_no         VARCHAR(24)  NOT NULL COMMENT '合并单号',
  preview_id       BIGINT       NOT NULL COMMENT '所依据的预演版本 id',
  keep_member_id   BIGINT       NOT NULL,
  source_member_id BIGINT       NOT NULL COMMENT '来源档案仍保留在 members 表（status=MERGED），不删除',
  keep_card_no     VARCHAR(24)  NOT NULL COMMENT '保留卡号',
  source_card_no   VARCHAR(24)  NOT NULL COMMENT '来源原卡号（留存可查）',
  keep_name        VARCHAR(32)  NOT NULL,
  source_name      VARCHAR(32)  NOT NULL COMMENT '来源原姓名（留存可查）',
  keep_balance_before   DECIMAL(10,2) NOT NULL,
  source_balance_before DECIMAL(10,2) NOT NULL,
  keep_spend_before     DECIMAL(12,2) NOT NULL,
  source_spend_before   DECIMAL(12,2) NOT NULL,
  balance_after    DECIMAL(10,2) NOT NULL,
  total_spend_after DECIMAL(12,2) NOT NULL,
  level_before     VARCHAR(16)  NOT NULL,
  level_after      VARCHAR(16)  NOT NULL,
  rounds_moved     INT          NOT NULL DEFAULT 0 COMMENT '迁移计分回合数（箭值 / 完成时间原样保留）',
  enrollments_moved INT         NOT NULL DEFAULT 0 COMMENT '迁移课程报名条数',
  enrollments_dropped INT       NOT NULL DEFAULT 0 COMMENT '同一课程重复报名去重条数（保留较早一条）',
  equipment_moved  INT          NOT NULL DEFAULT 0 COMMENT '迁移当前器材租借数',
  lanes_moved      INT          NOT NULL DEFAULT 0 COMMENT '迁移占用箭道数',
  certs_moved      INT          NOT NULL DEFAULT 0 COMMENT '迁移认证申请 / 有效认证数',
  tournament_moved INT          NOT NULL DEFAULT 0 COMMENT '迁移赛事队员 / 箭值数',
  detail_json      TEXT         NULL COMMENT '逐项迁移 / 去重明细',
  reason           VARCHAR(500) NOT NULL,
  operator         VARCHAR(32)  NOT NULL COMMENT '合并操作者（值班经理，留存可查）',
  merged_at        DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_merge_log_no (merge_no),
  KEY idx_merge_log_keep (keep_member_id),
  KEY idx_merge_log_source (source_member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rounds (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  round_no      VARCHAR(24)  NOT NULL COMMENT '回合号',
  member_id     BIGINT       NOT NULL,
  lane_id       BIGINT       NOT NULL,
  bow_type      VARCHAR(16)  NOT NULL DEFAULT 'RECURVE' COMMENT '本回合弓种：RECURVE 反曲弓 / COMPOUND 复合弓 / TRADITIONAL 传统弓',
  start_time    DATETIME     NOT NULL COMMENT '开始时间',
  completed_at  DATETIME     NULL COMMENT '完成（提交）时间；个人最好成绩同分平手时按它取更早的回合',
  arrow_count   INT          NOT NULL COMMENT '本组箭支数：6 或 12',
  total_score   INT          NOT NULL COMMENT '总分',
  average_score DECIMAL(5,2) NOT NULL COMMENT '平均环',
  personal_best TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否个人最好成绩',
  status        VARCHAR(16)  NOT NULL COMMENT 'ONGOING 进行中 / PAUSED 暂停（安全联锁） / SUBMITTED 已提交',
  PRIMARY KEY (id),
  UNIQUE KEY uk_round_no (round_no),
  KEY idx_round_member (member_id),
  KEY idx_round_lane (lane_id),
  KEY idx_round_bow (bow_type),
  CONSTRAINT fk_round_member FOREIGN KEY (member_id) REFERENCES members (id),
  CONSTRAINT fk_round_lane   FOREIGN KEY (lane_id)   REFERENCES lanes (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS arrow_score (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  ring       VARCHAR(4)  NOT NULL COMMENT '环数标记：X/10/9/.../1/M',
  ring_value INT         NOT NULL COMMENT '计分环值：X 与 10 均为 10，M 为 0',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Round 到 ArrowScore 的有序集合连接表：shot_index 由 Hibernate 的 @OrderColumn 维护，
-- 保证列表顺序 = 射箭顺序（第 0 支、第 1 支 ...）。
CREATE TABLE IF NOT EXISTS round_arrow (
  round_id   BIGINT NOT NULL,
  arrow_id   BIGINT NOT NULL,
  shot_index INT    NOT NULL,
  PRIMARY KEY (round_id, shot_index),
  UNIQUE KEY uk_round_arrow_arrow (arrow_id),
  CONSTRAINT fk_ra_round FOREIGN KEY (round_id) REFERENCES rounds (id),
  CONSTRAINT fk_ra_arrow FOREIGN KEY (arrow_id) REFERENCES arrow_score (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS courses (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  course_name VARCHAR(64) NOT NULL COMMENT '课程名',
  coach       VARCHAR(32) NOT NULL COMMENT '教练',
  level       VARCHAR(16) NOT NULL COMMENT 'BASIC 初级 / ADVANCED 进阶 / COMPETITION 竞技',
  class_time  DATETIME    NOT NULL COMMENT '上课时间',
  capacity    INT         NOT NULL COMMENT '人数上限',
  enrolled    INT         NOT NULL DEFAULT 0 COMMENT '已报名',
  venue       VARCHAR(32) NOT NULL COMMENT '教室/场地',
  PRIMARY KEY (id),
  KEY idx_course_time (class_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS course_enroll (
  id          BIGINT   NOT NULL AUTO_INCREMENT,
  course_id   BIGINT   NOT NULL,
  member_id   BIGINT   NOT NULL,
  enroll_time DATETIME NOT NULL COMMENT '报名时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_enroll (course_id, member_id),
  CONSTRAINT fk_enroll_course FOREIGN KEY (course_id) REFERENCES courses (id),
  CONSTRAINT fk_enroll_member FOREIGN KEY (member_id) REFERENCES members (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS equipment (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  equip_code  VARCHAR(24)  NOT NULL COMMENT '器材编号',
  type        VARCHAR(16)  NOT NULL COMMENT 'RECURVE 反曲弓 / COMPOUND 复合弓 / TRADITIONAL 传统弓 / GEAR 护具 / ARROW 箭支',
  brand       VARCHAR(32)  NOT NULL COMMENT '品牌',
  rent_price  DECIMAL(10,2) NOT NULL COMMENT '租金（元/小时）',
  status      VARCHAR(16)  NOT NULL COMMENT 'INSTOCK 在库 / RENTED 租出 / REPAIR 维修 / LOCKED 安全锁定',
  renter_id   BIGINT       NULL,
  renter_name VARCHAR(32)  NULL,
  rented_at   DATETIME     NULL COMMENT '租出时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_equip_code (equip_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ================= 安全停射联锁台 =================
-- 停射事件主表：值班经理创建后立即联锁箭道/回合/器材；
-- 状态机 PENDING 待处置 → REVIEWING 分项复核 → CLEARED 待放行 → RELEASED 已放行；
-- 任一复核不通过退回 PENDING（继续锁定），重新发起复核时 review_round +1。
CREATE TABLE IF NOT EXISTS safety_event (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  event_no     VARCHAR(24)  NOT NULL COMMENT '事件编号',
  reason       VARCHAR(24)  NOT NULL COMMENT '原因类别：LANE_DEVICE 箭道设备异常 / PERSON_INTRUSION 人员闯入射线 / EQUIP_SUSPECT 疑似器材故障 / OTHER 其他',
  severity     VARCHAR(16)  NOT NULL COMMENT '严重级别：NOTICE 一般 / MAJOR 严重 / CRITICAL 紧急',
  description  VARCHAR(500) NOT NULL COMMENT '现场说明',
  status       VARCHAR(16)  NOT NULL COMMENT 'PENDING / REVIEWING / CLEARED / RELEASED',
  review_round INT          NOT NULL DEFAULT 1 COMMENT '复核轮次',
  created_by   VARCHAR(32)  NOT NULL COMMENT '创建人（值班经理）',
  created_at   DATETIME     NOT NULL,
  released_by  VARCHAR(32)  NULL COMMENT '放行执行人（值班经理）',
  released_at  DATETIME     NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_safety_event_no (event_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 事件-箭道快照：prev_status 记录锁定前业务状态，放行时按此恢复，绝不粗暴覆盖。
CREATE TABLE IF NOT EXISTS safety_event_lane (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  event_id    BIGINT      NOT NULL,
  lane_id     BIGINT      NOT NULL,
  prev_status VARCHAR(16) NOT NULL COMMENT '锁定前状态：OPEN / OCCUPIED / MAINTENANCE',
  restored    TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '放行时是否已恢复',
  PRIMARY KEY (id),
  UNIQUE KEY uk_safety_lane (event_id, lane_id),
  CONSTRAINT fk_sl_event FOREIGN KEY (event_id) REFERENCES safety_event (id),
  CONSTRAINT fk_sl_lane  FOREIGN KEY (lane_id)  REFERENCES lanes (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 事件-器材快照：同样保留锁定前状态（租出中的器材放行后仍归原会员，renter 字段锁定期间不动）。
CREATE TABLE IF NOT EXISTS safety_event_equipment (
  id           BIGINT      NOT NULL AUTO_INCREMENT,
  event_id     BIGINT      NOT NULL,
  equipment_id BIGINT      NOT NULL,
  prev_status  VARCHAR(16) NOT NULL COMMENT '锁定前状态：INSTOCK / RENTED / REPAIR',
  restored     TINYINT(1)  NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_safety_equip (event_id, equipment_id),
  CONSTRAINT fk_se_event FOREIGN KEY (event_id)     REFERENCES safety_event (id),
  CONSTRAINT fk_se_equip FOREIGN KEY (equipment_id) REFERENCES equipment (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 事件-回合快照：受影响箭道上进行中的回合被暂停，放行后从原进度继续。
CREATE TABLE IF NOT EXISTS safety_event_round (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  event_id    BIGINT      NOT NULL,
  round_id    BIGINT      NOT NULL,
  prev_status VARCHAR(16) NOT NULL COMMENT '锁定前状态：ONGOING',
  restored    TINYINT(1)  NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_safety_round (event_id, round_id),
  CONSTRAINT fk_srd_event FOREIGN KEY (event_id) REFERENCES safety_event (id),
  CONSTRAINT fk_srd_round FOREIGN KEY (round_id) REFERENCES rounds (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 分项复核：每个事件每一轮有 RANGE（教练）与 EQUIPMENT（器材管理员）两项，
-- (event_id, review_round, item) 唯一，同一复核项只能形成一份最终结论。
CREATE TABLE IF NOT EXISTS safety_review (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  event_id     BIGINT       NOT NULL,
  review_round INT          NOT NULL,
  item         VARCHAR(16)  NOT NULL COMMENT 'RANGE 射线与人员安全 / EQUIPMENT 器材检查',
  conclusion   VARCHAR(8)   NULL COMMENT 'PASS 通过 / FAIL 不通过，NULL 为待复核',
  reviewer     VARCHAR(32)  NULL COMMENT '复核人',
  reviewed_at  DATETIME     NULL,
  note         VARCHAR(500) NULL COMMENT '复核意见；不通过时必填原因',
  PRIMARY KEY (id),
  UNIQUE KEY uk_safety_review (event_id, review_round, item),
  CONSTRAINT fk_srv_event FOREIGN KEY (event_id) REFERENCES safety_event (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 状态轨迹：创建、发起复核、每次复核结论、复核齐通过、退回待处置、复射放行，全程留痕。
CREATE TABLE IF NOT EXISTS safety_event_log (
  id         BIGINT       NOT NULL AUTO_INCREMENT,
  event_id   BIGINT       NOT NULL,
  action     VARCHAR(24)  NOT NULL COMMENT 'CREATE / START_REVIEW / REVIEW_PASS / REVIEW_FAIL / BACK_PENDING / CLEARED / RELEASE',
  operator   VARCHAR(32)  NOT NULL COMMENT '操作人',
  role       VARCHAR(16)  NOT NULL COMMENT 'MANAGER 值班经理 / COACH 教练 / KEEPER 器材管理员',
  detail     VARCHAR(500) NULL,
  created_at DATETIME     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_safety_log_event (event_id),
  CONSTRAINT fk_slog_event FOREIGN KEY (event_id) REFERENCES safety_event (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- lanes：12 行
INSERT INTO lanes (`id`, `lane_no`, `distance`, `target_type`, `status`, `hourly_price`, `occupant_id`, `occupant_name`, `opened_at`, `planned_hours`) VALUES (1, 'A01', 10, '纸质靶', 'OPEN', 60.00, NULL, NULL, NULL, NULL);
INSERT INTO lanes (`id`, `lane_no`, `distance`, `target_type`, `status`, `hourly_price`, `occupant_id`, `occupant_name`, `opened_at`, `planned_hours`) VALUES (2, 'A02', 10, '纸质靶', 'OCCUPIED', 60.00, 3, '赵启明', '2026-09-22 10:20:00', 2);
INSERT INTO lanes (`id`, `lane_no`, `distance`, `target_type`, `status`, `hourly_price`, `occupant_id`, `occupant_name`, `opened_at`, `planned_hours`) VALUES (3, 'A03', 18, '三联靶', 'OPEN', 80.00, NULL, NULL, NULL, NULL);
INSERT INTO lanes (`id`, `lane_no`, `distance`, `target_type`, `status`, `hourly_price`, `occupant_id`, `occupant_name`, `opened_at`, `planned_hours`) VALUES (4, 'A04', 18, '三联靶', 'OPEN', 80.00, NULL, NULL, NULL, NULL);
INSERT INTO lanes (`id`, `lane_no`, `distance`, `target_type`, `status`, `hourly_price`, `occupant_id`, `occupant_name`, `opened_at`, `planned_hours`) VALUES (5, 'A05', 18, '三联靶', 'MAINTENANCE', 80.00, NULL, NULL, NULL, NULL);
INSERT INTO lanes (`id`, `lane_no`, `distance`, `target_type`, `status`, `hourly_price`, `occupant_id`, `occupant_name`, `opened_at`, `planned_hours`) VALUES (6, 'A06', 30, '复合靶', 'OPEN', 100.00, NULL, NULL, NULL, NULL);
INSERT INTO lanes (`id`, `lane_no`, `distance`, `target_type`, `status`, `hourly_price`, `occupant_id`, `occupant_name`, `opened_at`, `planned_hours`) VALUES (7, 'A07', 30, '复合靶', 'OCCUPIED', 100.00, 7, '郑楠', '2026-09-22 13:05:00', 3);
INSERT INTO lanes (`id`, `lane_no`, `distance`, `target_type`, `status`, `hourly_price`, `occupant_id`, `occupant_name`, `opened_at`, `planned_hours`) VALUES (8, 'A08', 30, '复合靶', 'OCCUPIED', 100.00, 5, '周然', '2026-09-22 15:40:00', 1);
INSERT INTO lanes (`id`, `lane_no`, `distance`, `target_type`, `status`, `hourly_price`, `occupant_id`, `occupant_name`, `opened_at`, `planned_hours`) VALUES (9, 'A09', 50, '三联靶', 'OPEN', 140.00, NULL, NULL, NULL, NULL);
INSERT INTO lanes (`id`, `lane_no`, `distance`, `target_type`, `status`, `hourly_price`, `occupant_id`, `occupant_name`, `opened_at`, `planned_hours`) VALUES (10, 'A10', 50, '三联靶', 'OPEN', 140.00, NULL, NULL, NULL, NULL);
INSERT INTO lanes (`id`, `lane_no`, `distance`, `target_type`, `status`, `hourly_price`, `occupant_id`, `occupant_name`, `opened_at`, `planned_hours`) VALUES (11, 'A11', 50, '3D动物靶', 'MAINTENANCE', 120.00, NULL, NULL, NULL, NULL);
INSERT INTO lanes (`id`, `lane_no`, `distance`, `target_type`, `status`, `hourly_price`, `occupant_id`, `occupant_name`, `opened_at`, `planned_hours`) VALUES (12, 'A12', 30, '复合靶', 'OPEN', 100.00, NULL, NULL, NULL, NULL);

-- members：12 行
INSERT INTO members (`id`, `card_no`, `name`, `phone`, `level`, `balance`, `register_date`, `total_spend`) VALUES (1, 'AR-2023-0001', '陈子昂', '13800010001', 'GOLD', 2680.00, '2023-03-18', 12480.00);
INSERT INTO members (`id`, `card_no`, `name`, `phone`, `level`, `balance`, `register_date`, `total_spend`) VALUES (2, 'AR-2023-0002', '林悦', '13800010002', 'SILVER', 960.00, '2023-06-02', 3420.00);
INSERT INTO members (`id`, `card_no`, `name`, `phone`, `level`, `balance`, `register_date`, `total_spend`) VALUES (3, 'AR-2023-0003', '赵启明', '13800010003', 'NORMAL', 320.00, '2023-09-11', 680.00);
INSERT INTO members (`id`, `card_no`, `name`, `phone`, `level`, `balance`, `register_date`, `total_spend`) VALUES (4, 'AR-2024-0004', '孙嘉', '13800010004', 'GOLD', 4120.00, '2024-01-05', 15600.00);
INSERT INTO members (`id`, `card_no`, `name`, `phone`, `level`, `balance`, `register_date`, `total_spend`) VALUES (5, 'AR-2024-0005', '周然', '13800010005', 'NORMAL', 150.00, '2024-02-20', 240.00);
INSERT INTO members (`id`, `card_no`, `name`, `phone`, `level`, `balance`, `register_date`, `total_spend`) VALUES (6, 'AR-2024-0006', '吴桐', '13800010006', 'SILVER', 1280.00, '2024-04-09', 4100.00);
INSERT INTO members (`id`, `card_no`, `name`, `phone`, `level`, `balance`, `register_date`, `total_spend`) VALUES (7, 'AR-2024-0007', '郑楠', '13800010007', 'NORMAL', 480.00, '2024-05-16', 720.00);
INSERT INTO members (`id`, `card_no`, `name`, `phone`, `level`, `balance`, `register_date`, `total_spend`) VALUES (8, 'AR-2024-0008', '何清', '13800010008', 'GOLD', 3260.00, '2024-06-21', 10200.00);
INSERT INTO members (`id`, `card_no`, `name`, `phone`, `level`, `balance`, `register_date`, `total_spend`) VALUES (9, 'AR-2024-0009', '冯磊', '13800010009', 'SILVER', 880.00, '2024-07-30', 3200.00);
INSERT INTO members (`id`, `card_no`, `name`, `phone`, `level`, `balance`, `register_date`, `total_spend`) VALUES (10, 'AR-2025-0010', '许静', '13800010010', 'NORMAL', 260.00, '2025-01-12', 520.00);
INSERT INTO members (`id`, `card_no`, `name`, `phone`, `level`, `balance`, `register_date`, `total_spend`) VALUES (11, 'AR-2025-0011', '高翔', '13800010011', 'SILVER', 1560.00, '2025-03-08', 5200.00);
INSERT INTO members (`id`, `card_no`, `name`, `phone`, `level`, `balance`, `register_date`, `total_spend`) VALUES (12, 'AR-2025-0012', '唐宁', '13800010012', 'NORMAL', 100.00, '2025-05-27', 180.00);

-- rounds：13 行（bow_type 记录本回合使用弓种，是弓种认证的证据字段之一）
INSERT INTO rounds (`id`, `round_no`, `member_id`, `lane_id`, `bow_type`, `start_time`, `completed_at`, `arrow_count`, `total_score`, `average_score`, `personal_best`, `status`) VALUES (1, 'R20260915001', 1, 1, 'RECURVE', '2026-09-15 19:10:00', '2026-09-15 19:10:00', 6, 58, 9.67, 1, 'SUBMITTED');
INSERT INTO rounds (`id`, `round_no`, `member_id`, `lane_id`, `bow_type`, `start_time`, `completed_at`, `arrow_count`, `total_score`, `average_score`, `personal_best`, `status`) VALUES (2, 'R20260916001', 2, 3, 'TRADITIONAL', '2026-09-16 18:40:00', '2026-09-16 18:40:00', 6, 43, 7.17, 1, 'SUBMITTED');
INSERT INTO rounds (`id`, `round_no`, `member_id`, `lane_id`, `bow_type`, `start_time`, `completed_at`, `arrow_count`, `total_score`, `average_score`, `personal_best`, `status`) VALUES (3, 'R20260916002', 4, 6, 'COMPOUND', '2026-09-16 20:05:00', '2026-09-16 20:05:00', 12, 112, 9.33, 1, 'SUBMITTED');
INSERT INTO rounds (`id`, `round_no`, `member_id`, `lane_id`, `bow_type`, `start_time`, `completed_at`, `arrow_count`, `total_score`, `average_score`, `personal_best`, `status`) VALUES (4, 'R20260917001', 6, 4, 'RECURVE', '2026-09-17 19:30:00', '2026-09-17 19:30:00', 6, 45, 7.50, 1, 'SUBMITTED');
INSERT INTO rounds (`id`, `round_no`, `member_id`, `lane_id`, `bow_type`, `start_time`, `completed_at`, `arrow_count`, `total_score`, `average_score`, `personal_best`, `status`) VALUES (5, 'R20260918001', 8, 9, 'RECURVE', '2026-09-18 20:15:00', '2026-09-18 20:15:00', 12, 109, 9.08, 1, 'SUBMITTED');
INSERT INTO rounds (`id`, `round_no`, `member_id`, `lane_id`, `bow_type`, `start_time`, `completed_at`, `arrow_count`, `total_score`, `average_score`, `personal_best`, `status`) VALUES (6, 'R20260919001', 1, 10, 'RECURVE', '2026-09-19 19:00:00', '2026-09-19 19:00:00', 6, 58, 9.67, 0, 'SUBMITTED');
INSERT INTO rounds (`id`, `round_no`, `member_id`, `lane_id`, `bow_type`, `start_time`, `completed_at`, `arrow_count`, `total_score`, `average_score`, `personal_best`, `status`) VALUES (7, 'R20260920001', 9, 8, 'COMPOUND', '2026-09-20 18:20:00', '2026-09-20 18:20:00', 6, 37, 6.17, 1, 'SUBMITTED');
INSERT INTO rounds (`id`, `round_no`, `member_id`, `lane_id`, `bow_type`, `start_time`, `completed_at`, `arrow_count`, `total_score`, `average_score`, `personal_best`, `status`) VALUES (8, 'R20260920002', 11, 7, 'COMPOUND', '2026-09-20 20:30:00', '2026-09-20 20:30:00', 12, 104, 8.67, 1, 'SUBMITTED');
INSERT INTO rounds (`id`, `round_no`, `member_id`, `lane_id`, `bow_type`, `start_time`, `completed_at`, `arrow_count`, `total_score`, `average_score`, `personal_best`, `status`) VALUES (9, 'R20260921001', 4, 12, 'COMPOUND', '2026-09-21 19:45:00', '2026-09-21 19:45:00', 6, 59, 9.83, 0, 'SUBMITTED');
INSERT INTO rounds (`id`, `round_no`, `member_id`, `lane_id`, `bow_type`, `start_time`, `completed_at`, `arrow_count`, `total_score`, `average_score`, `personal_best`, `status`) VALUES (10, 'R20260922001', 3, 2, 'RECURVE', '2026-09-22 10:20:00', NULL, 6, 0, 0.00, 0, 'ONGOING');
INSERT INTO rounds (`id`, `round_no`, `member_id`, `lane_id`, `bow_type`, `start_time`, `completed_at`, `arrow_count`, `total_score`, `average_score`, `personal_best`, `status`) VALUES (11, 'R20260922002', 7, 7, 'COMPOUND', '2026-09-22 13:05:00', NULL, 12, 0, 0.00, 0, 'ONGOING');
INSERT INTO rounds (`id`, `round_no`, `member_id`, `lane_id`, `bow_type`, `start_time`, `completed_at`, `arrow_count`, `total_score`, `average_score`, `personal_best`, `status`) VALUES (12, 'R20260922003', 5, 8, 'RECURVE', '2026-09-22 15:40:00', NULL, 6, 0, 0.00, 0, 'ONGOING');
-- 历史回合：会员 2 去年的传统弓 18 米训练，用于一笔已过期认证的证据
INSERT INTO rounds (`id`, `round_no`, `member_id`, `lane_id`, `bow_type`, `start_time`, `completed_at`, `arrow_count`, `total_score`, `average_score`, `personal_best`, `status`) VALUES (13, 'R20250910001', 2, 3, 'TRADITIONAL', '2025-09-10 19:00:00', '2025-09-10 19:00:00', 6, 40, 6.67, 0, 'SUBMITTED');

-- arrow_score：72 行
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (1, 'X', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (2, '10', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (3, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (4, '10', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (5, 'X', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (6, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (7, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (8, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (9, '8', 8);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (10, '10', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (11, '7', 7);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (12, 'M', 0);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (13, 'X', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (14, '10', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (15, '10', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (16, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (17, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (18, '8', 8);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (19, '10', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (20, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (21, 'X', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (22, '10', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (23, '8', 8);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (24, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (25, '8', 8);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (26, '7', 7);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (27, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (28, '6', 6);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (29, '8', 8);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (30, '7', 7);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (31, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (32, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (33, '10', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (34, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (35, '8', 8);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (36, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (37, '10', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (38, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (39, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (40, '8', 8);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (41, '10', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (42, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (43, '10', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (44, '10', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (45, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (46, 'X', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (47, '10', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (48, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (49, '7', 7);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (50, '6', 6);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (51, '8', 8);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (52, '7', 7);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (53, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (54, 'M', 0);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (55, '8', 8);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (56, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (57, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (58, '10', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (59, '8', 8);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (60, '7', 7);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (61, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (62, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (63, '8', 8);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (64, '10', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (65, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (66, '8', 8);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (67, 'X', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (68, 'X', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (69, '10', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (70, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (71, '10', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (72, '10', 10);
-- round 13（历史传统弓训练）6 支：7/7/6/8/6/6 = 40
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (73, '7', 7);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (74, '7', 7);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (75, '6', 6);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (76, '8', 8);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (77, '6', 6);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (78, '6', 6);

-- round_arrow：72 行
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (1, 1, 0);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (1, 2, 1);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (1, 3, 2);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (1, 4, 3);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (1, 5, 4);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (1, 6, 5);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (2, 7, 0);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (2, 8, 1);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (2, 9, 2);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (2, 10, 3);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (2, 11, 4);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (2, 12, 5);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (3, 13, 0);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (3, 14, 1);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (3, 15, 2);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (3, 16, 3);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (3, 17, 4);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (3, 18, 5);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (3, 19, 6);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (3, 20, 7);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (3, 21, 8);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (3, 22, 9);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (3, 23, 10);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (3, 24, 11);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (4, 25, 0);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (4, 26, 1);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (4, 27, 2);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (4, 28, 3);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (4, 29, 4);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (4, 30, 5);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (5, 31, 0);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (5, 32, 1);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (5, 33, 2);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (5, 34, 3);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (5, 35, 4);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (5, 36, 5);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (5, 37, 6);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (5, 38, 7);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (5, 39, 8);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (5, 40, 9);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (5, 41, 10);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (5, 42, 11);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (6, 43, 0);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (6, 44, 1);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (6, 45, 2);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (6, 46, 3);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (6, 47, 4);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (6, 48, 5);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (7, 49, 0);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (7, 50, 1);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (7, 51, 2);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (7, 52, 3);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (7, 53, 4);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (7, 54, 5);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (8, 55, 0);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (8, 56, 1);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (8, 57, 2);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (8, 58, 3);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (8, 59, 4);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (8, 60, 5);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (8, 61, 6);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (8, 62, 7);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (8, 63, 8);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (8, 64, 9);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (8, 65, 10);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (8, 66, 11);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (9, 67, 0);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (9, 68, 1);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (9, 69, 2);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (9, 70, 3);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (9, 71, 4);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (9, 72, 5);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (13, 73, 0);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (13, 74, 1);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (13, 75, 2);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (13, 76, 3);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (13, 77, 4);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (13, 78, 5);

-- courses：10 行
INSERT INTO courses (`id`, `course_name`, `coach`, `level`, `class_time`, `capacity`, `enrolled`, `venue`) VALUES (1, '反曲弓入门体验', '张岩', 'BASIC', '2026-09-23 19:00:00', 8, 5, '一号教学区');
INSERT INTO courses (`id`, `course_name`, `coach`, `level`, `class_time`, `capacity`, `enrolled`, `venue`) VALUES (2, '站姿与撒放基础', '张岩', 'BASIC', '2026-09-24 19:30:00', 8, 4, '一号教学区');
INSERT INTO courses (`id`, `course_name`, `coach`, `level`, `class_time`, `capacity`, `enrolled`, `venue`) VALUES (3, '瞄点与稳定训练', '李慕白', 'ADVANCED', '2026-09-25 20:00:00', 6, 3, '二号教学区');
INSERT INTO courses (`id`, `course_name`, `coach`, `level`, `class_time`, `capacity`, `enrolled`, `venue`) VALUES (4, '复合弓调校实操', '王铮', 'ADVANCED', '2026-09-26 19:00:00', 6, 3, '器材工坊');
INSERT INTO courses (`id`, `course_name`, `coach`, `level`, `class_time`, `capacity`, `enrolled`, `venue`) VALUES (5, '30米定距强化', '李慕白', 'ADVANCED', '2026-09-27 20:00:00', 6, 6, '二号教学区');
INSERT INTO courses (`id`, `course_name`, `coach`, `level`, `class_time`, `capacity`, `enrolled`, `venue`) VALUES (6, '竞技反曲特训班', '陈亦驰', 'COMPETITION', '2026-09-28 19:00:00', 4, 3, '竞技馆');
INSERT INTO courses (`id`, `course_name`, `coach`, `level`, `class_time`, `capacity`, `enrolled`, `venue`) VALUES (7, '室内18米积分赛', '陈亦驰', 'COMPETITION', '2026-09-29 19:30:00', 4, 2, '竞技馆');
INSERT INTO courses (`id`, `course_name`, `coach`, `level`, `class_time`, `capacity`, `enrolled`, `venue`) VALUES (8, '传统弓礼仪与技法', '苏禾', 'BASIC', '2026-09-30 19:00:00', 10, 3, '三号教学区');
INSERT INTO courses (`id`, `course_name`, `coach`, `level`, `class_time`, `capacity`, `enrolled`, `venue`) VALUES (9, '青少年安全射箭课', '苏禾', 'BASIC', '2026-10-01 10:00:00', 10, 5, '一号教学区');
INSERT INTO courses (`id`, `course_name`, `coach`, `level`, `class_time`, `capacity`, `enrolled`, `venue`) VALUES (10, '赛前心理与节奏', '陈亦驰', 'COMPETITION', '2026-10-02 19:00:00', 4, 2, '竞技馆');

-- course_enroll：36 行
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (1, 1, 1, '2026-09-13 09:10:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (2, 1, 2, '2026-09-13 09:17:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (3, 1, 3, '2026-09-13 09:24:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (4, 1, 5, '2026-09-13 09:31:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (5, 1, 7, '2026-09-13 09:38:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (6, 2, 3, '2026-09-14 09:10:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (7, 2, 5, '2026-09-14 09:17:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (8, 2, 10, '2026-09-14 09:24:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (9, 2, 12, '2026-09-14 09:31:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (10, 3, 2, '2026-09-15 09:10:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (11, 3, 6, '2026-09-15 09:17:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (12, 3, 11, '2026-09-15 09:24:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (13, 4, 4, '2026-09-16 09:10:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (14, 4, 6, '2026-09-16 09:17:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (15, 4, 9, '2026-09-16 09:24:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (16, 5, 1, '2026-09-17 09:10:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (17, 5, 2, '2026-09-17 09:17:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (18, 5, 4, '2026-09-17 09:24:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (19, 5, 6, '2026-09-17 09:31:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (20, 5, 9, '2026-09-17 09:38:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (21, 5, 11, '2026-09-17 09:45:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (22, 6, 1, '2026-09-18 09:10:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (23, 6, 4, '2026-09-18 09:17:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (24, 6, 8, '2026-09-18 09:24:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (25, 7, 4, '2026-09-19 09:10:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (26, 7, 8, '2026-09-19 09:17:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (27, 8, 7, '2026-09-20 09:10:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (28, 8, 10, '2026-09-20 09:17:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (29, 8, 12, '2026-09-20 09:24:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (30, 9, 3, '2026-09-12 09:10:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (31, 9, 5, '2026-09-12 09:17:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (32, 9, 7, '2026-09-12 09:24:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (33, 9, 10, '2026-09-12 09:31:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (34, 9, 12, '2026-09-12 09:38:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (35, 10, 1, '2026-09-13 09:10:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (36, 10, 8, '2026-09-13 09:17:00');

-- equipment：12 行
INSERT INTO equipment (`id`, `equip_code`, `type`, `brand`, `rent_price`, `status`, `renter_id`, `renter_name`, `rented_at`) VALUES (1, 'E-RC-01', 'RECURVE', 'Hoyt', 45.00, 'INSTOCK', NULL, NULL, NULL);
INSERT INTO equipment (`id`, `equip_code`, `type`, `brand`, `rent_price`, `status`, `renter_id`, `renter_name`, `rented_at`) VALUES (2, 'E-RC-02', 'RECURVE', 'W&W', 40.00, 'RENTED', 3, '赵启明', '2026-09-22 10:25:00');
INSERT INTO equipment (`id`, `equip_code`, `type`, `brand`, `rent_price`, `status`, `renter_id`, `renter_name`, `rented_at`) VALUES (3, 'E-RC-03', 'RECURVE', 'KAP', 38.00, 'INSTOCK', NULL, NULL, NULL);
INSERT INTO equipment (`id`, `equip_code`, `type`, `brand`, `rent_price`, `status`, `renter_id`, `renter_name`, `rented_at`) VALUES (4, 'E-CP-01', 'COMPOUND', 'Mathews', 80.00, 'INSTOCK', NULL, NULL, NULL);
INSERT INTO equipment (`id`, `equip_code`, `type`, `brand`, `rent_price`, `status`, `renter_id`, `renter_name`, `rented_at`) VALUES (5, 'E-CP-02', 'COMPOUND', 'Hoyt', 75.00, 'RENTED', 7, '郑楠', '2026-09-22 13:10:00');
INSERT INTO equipment (`id`, `equip_code`, `type`, `brand`, `rent_price`, `status`, `renter_id`, `renter_name`, `rented_at`) VALUES (6, 'E-CP-03', 'COMPOUND', 'PSE', 70.00, 'INSTOCK', NULL, NULL, NULL);
INSERT INTO equipment (`id`, `equip_code`, `type`, `brand`, `rent_price`, `status`, `renter_id`, `renter_name`, `rented_at`) VALUES (7, 'E-TR-01', 'TRADITIONAL', '三利达', 35.00, 'INSTOCK', NULL, NULL, NULL);
INSERT INTO equipment (`id`, `equip_code`, `type`, `brand`, `rent_price`, `status`, `renter_id`, `renter_name`, `rented_at`) VALUES (8, 'E-TR-02', 'TRADITIONAL', '黑石', 30.00, 'REPAIR', NULL, NULL, NULL);
INSERT INTO equipment (`id`, `equip_code`, `type`, `brand`, `rent_price`, `status`, `renter_id`, `renter_name`, `rented_at`) VALUES (9, 'E-GR-01', 'GEAR', 'Fivics', 12.00, 'INSTOCK', NULL, NULL, NULL);
INSERT INTO equipment (`id`, `equip_code`, `type`, `brand`, `rent_price`, `status`, `renter_id`, `renter_name`, `rented_at`) VALUES (10, 'E-GR-02', 'GEAR', 'Cartel', 15.00, 'RENTED', 5, '周然', '2026-09-22 15:45:00');
INSERT INTO equipment (`id`, `equip_code`, `type`, `brand`, `rent_price`, `status`, `renter_id`, `renter_name`, `rented_at`) VALUES (11, 'E-AR-01', 'ARROW', 'Easton', 20.00, 'INSTOCK', NULL, NULL, NULL);
INSERT INTO equipment (`id`, `equip_code`, `type`, `brand`, `rent_price`, `status`, `renter_id`, `renter_name`, `rented_at`) VALUES (12, 'E-AR-02', 'ARROW', 'Gold Tip', 18.00, 'INSTOCK', NULL, NULL, NULL);

-- ================= 模块七：馆内团体淘汰赛计分台 =================
-- 赛事：值班经理建立，选择 4 或 8 支队（每队固定三名现有会员），设置每场局数。
-- 状态机 DRAFT 报名中（可换队/换队员）→ ONGOING 已开赛（队伍与对阵冻结）→ FINISHED 已完赛。
CREATE TABLE IF NOT EXISTS tournament (
  id              BIGINT      NOT NULL AUTO_INCREMENT,
  name            VARCHAR(64) NOT NULL COMMENT '赛事名称',
  team_size       INT         NOT NULL COMMENT '参赛队数：4 或 8',
  ends_per_match  INT         NOT NULL COMMENT '每场局数（每局三名队员各射规定箭数）',
  arrows_per_end  INT         NOT NULL COMMENT '每名队员每局规定箭数',
  status          VARCHAR(16) NOT NULL COMMENT 'DRAFT 报名中 / ONGOING 进行中 / FINISHED 已完赛',
  current_round   INT         NULL COMMENT '当前轮次：最早存在未确认场次的那一轮',
  champion_team_id BIGINT     NULL COMMENT '冠军队',
  created_by      VARCHAR(32) NOT NULL COMMENT '建立人（值班经理）',
  created_at      DATETIME    NOT NULL,
  started_at      DATETIME    NULL COMMENT '开赛时间（开赛即冻结队伍与对阵）',
  finished_at     DATETIME    NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 参赛队：seed_no 为开赛后固定的种子位（决定首轮对阵与晋级走向）
CREATE TABLE IF NOT EXISTS tournament_team (
  id            BIGINT      NOT NULL AUTO_INCREMENT,
  tournament_id BIGINT      NOT NULL,
  team_name     VARCHAR(48) NOT NULL,
  seed_no       INT         NULL COMMENT '种子位（开赛时按建队顺序固定，1..team_size）',
  final_rank    INT         NULL COMMENT '最终名次（冠军=1）',
  created_at    DATETIME    NOT NULL,
  PRIMARY KEY (id),
  KEY idx_team_tournament (tournament_id),
  CONSTRAINT fk_team_tournament FOREIGN KEY (tournament_id) REFERENCES tournament (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 队-会员：一队固定三名会员；(tournament_id, member_id) 唯一，同一会员不能同时出现在两支队伍
CREATE TABLE IF NOT EXISTS tournament_team_member (
  id            BIGINT      NOT NULL AUTO_INCREMENT,
  team_id       BIGINT      NOT NULL,
  tournament_id BIGINT      NOT NULL,
  member_id     BIGINT      NOT NULL,
  position      INT         NOT NULL COMMENT '队内站位 0/1/2',
  PRIMARY KEY (id),
  UNIQUE KEY uk_team_member (team_id, member_id),
  UNIQUE KEY uk_tournament_member (tournament_id, member_id),
  KEY idx_tmem_member (member_id),
  CONSTRAINT fk_tmem_team FOREIGN KEY (team_id) REFERENCES tournament_team (id) ON DELETE CASCADE,
  CONSTRAINT fk_tmem_tournament FOREIGN KEY (tournament_id) REFERENCES tournament (id) ON DELETE CASCADE,
  CONSTRAINT fk_tmem_member FOREIGN KEY (member_id) REFERENCES members (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 对阵场次：round_no 从 1 开始（1=首轮，越靠后越深），slot 为该轮场次序号。
-- 首轮主客队直接落种子队；后续轮靠 home_from_match_id / away_from_match_id 指明晋级来源，
-- 上一场确认胜者后由后端自动带入对应槽位（不靠前端拼接）。
-- 状态 PENDING 待开赛 → ONGOING 进行中 → AWAIT_CONFIRM 待确认胜者 → CONFIRMED 已确认。
-- 阶段 stage：REGULATION 规定局 / SHOOTOFF 加赛箭。
CREATE TABLE IF NOT EXISTS tournament_match (
  id                   BIGINT      NOT NULL AUTO_INCREMENT,
  tournament_id        BIGINT      NOT NULL,
  round_no             INT         NOT NULL,
  slot                 INT         NOT NULL,
  match_no             INT         NOT NULL COMMENT '场次号：全赛事按轮次统一编号',
  home_team_id         BIGINT      NULL,
  away_team_id         BIGINT      NULL,
  winner_team_id       BIGINT      NULL,
  status               VARCHAR(16) NOT NULL,
  stage                VARCHAR(16) NOT NULL DEFAULT 'REGULATION',
  home_score           INT         NOT NULL DEFAULT 0 COMMENT '规定局主队总分',
  away_score           INT         NOT NULL DEFAULT 0,
  home_x_count         INT         NOT NULL DEFAULT 0,
  away_x_count         INT         NOT NULL DEFAULT 0,
  shootoff_round_count INT         NOT NULL DEFAULT 0 COMMENT '已进行的加赛轮数',
  home_from_match_id   BIGINT      NULL COMMENT '主队晋级来源场次',
  away_from_match_id   BIGINT      NULL COMMENT '客队晋级来源场次',
  confirmed_by         VARCHAR(32) NULL,
  confirmed_at         DATETIME    NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_match_round_slot (tournament_id, round_no, slot),
  KEY idx_match_tournament (tournament_id),
  KEY idx_match_home_team (home_team_id),
  KEY idx_match_away_team (away_team_id),
  CONSTRAINT fk_match_tournament FOREIGN KEY (tournament_id) REFERENCES tournament (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 局：确认后不可改写；撤回即物理删除最后一局并在 tournament_log 留撤回人/时间/原因
CREATE TABLE IF NOT EXISTS match_end (
  id            BIGINT      NOT NULL AUTO_INCREMENT,
  match_id      BIGINT      NOT NULL,
  end_no        INT         NOT NULL COMMENT '局号，从 1 连续',
  home_score    INT         NOT NULL,
  away_score    INT         NOT NULL,
  home_x_count  INT         NOT NULL,
  away_x_count  INT         NOT NULL,
  status        VARCHAR(12) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT 录入中 / CONFIRMED 已确认',
  confirmed_by  VARCHAR(32) NOT NULL,
  confirmed_at  DATETIME    NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_match_end (match_id, end_no),
  KEY idx_end_match (match_id),
  CONSTRAINT fk_end_match FOREIGN KEY (match_id) REFERENCES tournament_match (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 局内箭值：唯一键 (end_id, member_id, shot_index)，未确认局可就地覆盖更正
CREATE TABLE IF NOT EXISTS match_arrow (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  end_id      BIGINT      NOT NULL,
  match_id    BIGINT      NOT NULL,
  team_id     BIGINT      NOT NULL,
  member_id   BIGINT      NOT NULL,
  shot_index  INT         NOT NULL COMMENT '该局该队员第几支，0..arrows_per_end-1',
  ring        VARCHAR(4)  NOT NULL COMMENT 'X/10..1/M',
  ring_value  INT         NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_end_member_shot (end_id, member_id, shot_index),
  KEY idx_arrow_match (match_id),
  KEY idx_arrow_member (member_id),
  CONSTRAINT fk_arrow_end FOREIGN KEY (end_id) REFERENCES match_end (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 加赛轮：一轮每名队员各射一支；先比加赛总分，再比 X 数，仍平则开下一轮
CREATE TABLE IF NOT EXISTS shootoff_round (
  id           BIGINT      NOT NULL AUTO_INCREMENT,
  match_id     BIGINT      NOT NULL,
  round_no     INT         NOT NULL COMMENT '加赛轮号，从 1 连续',
  home_score   INT         NOT NULL DEFAULT 0,
  away_score   INT         NOT NULL DEFAULT 0,
  home_x_count INT         NOT NULL DEFAULT 0,
  away_x_count INT         NOT NULL DEFAULT 0,
  winner_team_id BIGINT    NULL COMMENT '本轮产生的胜者；NULL 表示仍平、需继续加赛',
  status       VARCHAR(12) NOT NULL COMMENT 'DRAFT 录入中 / LOCKED 已锁定',
  locked_by    VARCHAR(32) NULL,
  locked_at    DATETIME    NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_shootoff_match_round (match_id, round_no),
  KEY idx_shootoff_match (match_id),
  CONSTRAINT fk_shootoff_match FOREIGN KEY (match_id) REFERENCES tournament_match (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS shootoff_arrow (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  round_id    BIGINT      NOT NULL,
  match_id    BIGINT      NOT NULL,
  team_id     BIGINT      NOT NULL,
  member_id   BIGINT      NOT NULL,
  ring        VARCHAR(4)  NOT NULL,
  ring_value  INT         NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_shootoff_arrow (round_id, member_id),
  KEY idx_soarrow_match (match_id),
  CONSTRAINT fk_soarrow_round FOREIGN KEY (round_id) REFERENCES shootoff_round (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 完整操作轨迹：建队/换队/开赛/确认局/撤回局/锁定加赛轮/确认胜者等全程留痕
CREATE TABLE IF NOT EXISTS tournament_log (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  tournament_id BIGINT       NOT NULL,
  match_id      BIGINT       NULL,
  action        VARCHAR(28)  NOT NULL,
  operator      VARCHAR(32)  NOT NULL,
  role          VARCHAR(16)  NOT NULL COMMENT 'MANAGER 值班经理 / REFEREE 裁判 / SYSTEM 系统',
  detail        VARCHAR(500) NULL,
  created_at    DATETIME     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_tlog_tournament (tournament_id),
  KEY idx_tlog_match (match_id),
  CONSTRAINT fk_tlog_tournament FOREIGN KEY (tournament_id) REFERENCES tournament (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ================= 模块八：弓种能力认证 =================
-- 认证规则（带版本）：同一弓种+射距为一条规则谱系（rule_code，如 RECURVE-50），
-- version 从 1 递增。规则调整 = 发布新版本，旧版本转 RETIRED：
-- 既不能改写已发出的认证历史（历史认证持有评定当时的规则快照），新申请也只能选 ACTIVE 版本。
CREATE TABLE IF NOT EXISTS cert_rule (
  id               BIGINT        NOT NULL AUTO_INCREMENT,
  rule_code        VARCHAR(24)   NOT NULL COMMENT '规则谱系：弓种-射距，如 RECURVE-50',
  version          INT           NOT NULL COMMENT '版本号，从 1 递增',
  bow_type         VARCHAR(16)   NOT NULL COMMENT '弓种：RECURVE 反曲弓 / COMPOUND 复合弓 / TRADITIONAL 传统弓',
  distance         INT           NOT NULL COMMENT '射距（米）：10/18/30/50',
  group_size       INT           NOT NULL COMMENT '每组箭数要求：6 或 12',
  min_rounds       INT           NOT NULL COMMENT '证据窗口内最少完成回合数',
  min_average      DECIMAL(4,2)  NOT NULL COMMENT '通过标准：证据窗口平均每支箭环值下限',
  validity_months  INT           NOT NULL COMMENT '认证通过后的有效期（月）',
  status           VARCHAR(16)   NOT NULL COMMENT 'ACTIVE 现行 / RETIRED 已停用',
  note             VARCHAR(200)  NULL COMMENT '规则说明（如标准上调原因）',
  created_by       VARCHAR(32)   NOT NULL COMMENT '发布教练',
  created_at       DATETIME      NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_cert_rule_version (rule_code, version),
  KEY idx_cert_rule_type (bow_type, distance)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 认证申请：教练选定会员、弓种与规则版本，划定有明确起止的证据窗口建立。
-- 状态机：PENDING 待复核 → APPROVED 已通过 / REJECTED 已驳回 / NEED_MORE 待补充证据；
-- NEED_MORE 可补充证据重新提交（回到 PENDING，eval_seq+1，旧评定保留）；
-- APPROVED 可被 WITHDRAWN 撤回，或被同一适用范围的新认证 SUPERSEDED 取代；
-- 过期不落库改状态：APPROVED 且 valid_until < 当前时间即视为已过期，任何入口都不得再当有效认证。
CREATE TABLE IF NOT EXISTS cert_application (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  app_no          VARCHAR(24)  NOT NULL COMMENT '申请号',
  member_id       BIGINT       NOT NULL,
  bow_type        VARCHAR(16)  NOT NULL COMMENT '申请弓种',
  distance        INT          NOT NULL COMMENT '申请射距（米）',
  window_start    DATETIME     NOT NULL COMMENT '证据窗口起',
  window_end      DATETIME     NOT NULL COMMENT '证据窗口止',
  status          VARCHAR(16)  NOT NULL COMMENT 'PENDING / APPROVED / REJECTED / NEED_MORE / WITHDRAWN / SUPERSEDED',
  eval_seq        INT          NOT NULL DEFAULT 1 COMMENT '当前评定序号：补充证据重新评定 +1',
  reviewed_by     VARCHAR(32)  NULL COMMENT '复核教练',
  reviewed_at     DATETIME     NULL,
  review_note     VARCHAR(500) NULL COMMENT '复核意见；驳回 / 要求补充证据时必填',
  valid_from      DATETIME     NULL COMMENT '认证生效时间（复核通过）',
  valid_until     DATETIME     NULL COMMENT '认证有效期至',
  withdrawn_by    VARCHAR(32)  NULL,
  withdrawn_at    DATETIME     NULL,
  withdraw_reason VARCHAR(500) NULL,
  created_by      VARCHAR(32)  NOT NULL COMMENT '建立申请的教练',
  created_at      DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_cert_app_no (app_no),
  KEY idx_cert_app_member (member_id),
  KEY idx_cert_app_scope (member_id, bow_type, distance),
  CONSTRAINT fk_cert_app_member FOREIGN KEY (member_id) REFERENCES members (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 评定记录（每次建立申请 / 补充证据后系统按规则快照评定一次）：
-- 完整保存当时选定的规则快照（弓种、箭数、最少回合、通过标准、有效期）与证据窗口，
-- 后续规则调整、窗口改选都不会改写历史评定。
CREATE TABLE IF NOT EXISTS cert_eval (
  id                   BIGINT        NOT NULL AUTO_INCREMENT,
  application_id       BIGINT        NOT NULL,
  eval_seq             INT           NOT NULL COMMENT '评定序号：首次 1，补充证据 +1',
  rule_id              BIGINT        NOT NULL COMMENT '评定使用的规则版本 id',
  rule_code            VARCHAR(24)   NOT NULL,
  rule_version         INT           NOT NULL,
  snap_group_size      INT           NOT NULL COMMENT '规则快照：每组箭数',
  snap_min_rounds      INT           NOT NULL COMMENT '规则快照：最少回合数',
  snap_min_average     DECIMAL(4,2)  NOT NULL COMMENT '规则快照：平均环下限',
  snap_validity_months INT           NOT NULL COMMENT '规则快照：有效期月数',
  window_start         DATETIME      NOT NULL,
  window_end           DATETIME      NOT NULL,
  round_count          INT           NOT NULL COMMENT '本次评定采信回合数',
  arrow_total          INT           NOT NULL COMMENT '证据箭支总数',
  total_score          INT           NOT NULL COMMENT '证据总环数',
  avg_score            DECIMAL(5,2)  NOT NULL COMMENT '证据平均环/支',
  pass_flag            TINYINT(1)    NOT NULL COMMENT '系统预评是否达到通过标准（待复核结果）',
  eval_message         VARCHAR(500)  NOT NULL COMMENT '预评说明：采信情况与达标判定',
  created_by           VARCHAR(32)   NOT NULL,
  created_at           DATETIME      NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_cert_eval_seq (application_id, eval_seq),
  KEY idx_cert_eval_rule (rule_id),
  CONSTRAINT fk_cert_eval_app  FOREIGN KEY (application_id) REFERENCES cert_application (id),
  CONSTRAINT fk_cert_eval_rule FOREIGN KEY (rule_id) REFERENCES cert_rule (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 评定-回合：本次评定具体采信了哪些计分回合（必须 SUBMITTED、弓种/射距/箭数匹配且落在窗口内）
CREATE TABLE IF NOT EXISTS cert_eval_round (
  id        BIGINT NOT NULL AUTO_INCREMENT,
  eval_id   BIGINT NOT NULL,
  round_id  BIGINT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_cert_eval_round (eval_id, round_id),
  KEY idx_cer_round (round_id),
  CONSTRAINT fk_cer_eval  FOREIGN KEY (eval_id) REFERENCES cert_eval (id),
  CONSTRAINT fk_cer_round FOREIGN KEY (round_id) REFERENCES rounds (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 状态轨迹：建立 / 系统预评 / 通过 / 驳回 / 要求补充 / 补充重评 / 撤回 / 被取代，全程留痕
CREATE TABLE IF NOT EXISTS cert_log (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  application_id BIGINT       NOT NULL,
  action         VARCHAR(24)  NOT NULL COMMENT 'CREATE / EVAL / APPROVE / REJECT / NEED_MORE / RESUBMIT / WITHDRAW / SUPERSEDE',
  operator       VARCHAR(32)  NOT NULL,
  role           VARCHAR(16)  NOT NULL COMMENT 'COACH 教练 / SYSTEM 认证系统',
  detail         VARCHAR(500) NULL,
  created_at     DATETIME     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_cert_log_app (application_id),
  CONSTRAINT fk_cert_log_app FOREIGN KEY (application_id) REFERENCES cert_application (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- cert_rule：6 行（RECURVE-50 同时存在已停用 v1 与现行 v2，演示版本化规则）
INSERT INTO cert_rule (`id`, `rule_code`, `version`, `bow_type`, `distance`, `group_size`, `min_rounds`, `min_average`, `validity_months`, `status`, `note`, `created_by`, `created_at`) VALUES (1, 'RECURVE-10', 1, 'RECURVE', 10, 6, 1, 6.00, 12, 'ACTIVE', '反曲弓 10 米基础认证：窗口平均每支箭不低于 6 环', '张岩', '2026-09-01 10:00:00');
INSERT INTO cert_rule (`id`, `rule_code`, `version`, `bow_type`, `distance`, `group_size`, `min_rounds`, `min_average`, `validity_months`, `status`, `note`, `created_by`, `created_at`) VALUES (2, 'RECURVE-30', 1, 'RECURVE', 30, 6, 2, 7.00, 12, 'ACTIVE', '反曲弓 30 米认证：窗口内至少 2 个完成回合，平均不低于 7 环', '张岩', '2026-09-01 10:00:00');
INSERT INTO cert_rule (`id`, `rule_code`, `version`, `bow_type`, `distance`, `group_size`, `min_rounds`, `min_average`, `validity_months`, `status`, `note`, `created_by`, `created_at`) VALUES (3, 'RECURVE-50', 1, 'RECURVE', 50, 6, 1, 7.50, 12, 'RETIRED', '反曲弓 50 米认证旧版：平均下限 7.5 环（已被 v2 取代，历史认证仍按本快照）', '张岩', '2026-09-01 10:00:00');
INSERT INTO cert_rule (`id`, `rule_code`, `version`, `bow_type`, `distance`, `group_size`, `min_rounds`, `min_average`, `validity_months`, `status`, `note`, `created_by`, `created_at`) VALUES (4, 'RECURVE-50', 2, 'RECURVE', 50, 6, 1, 8.00, 12, 'ACTIVE', '反曲弓 50 米认证现行版：平均环下限由 7.5 上调至 8.0', '张岩', '2026-09-18 10:00:00');
INSERT INTO cert_rule (`id`, `rule_code`, `version`, `bow_type`, `distance`, `group_size`, `min_rounds`, `min_average`, `validity_months`, `status`, `note`, `created_by`, `created_at`) VALUES (5, 'COMPOUND-30', 1, 'COMPOUND', 30, 12, 1, 8.00, 12, 'ACTIVE', '复合弓 30 米认证：12 支组完成回合，平均不低于 8 环', '王铮', '2026-09-01 10:00:00');
INSERT INTO cert_rule (`id`, `rule_code`, `version`, `bow_type`, `distance`, `group_size`, `min_rounds`, `min_average`, `validity_months`, `status`, `note`, `created_by`, `created_at`) VALUES (6, 'TRADITIONAL-18', 1, 'TRADITIONAL', 18, 6, 1, 6.00, 12, 'ACTIVE', '传统弓 18 米认证：窗口平均每支箭不低于 6 环', '苏禾', '2026-09-01 10:00:00');

-- cert_application：3 行（一笔现行有效 / 一笔已过期 / 一笔已撤回，覆盖会员可用状态变化）
INSERT INTO cert_application (`id`, `app_no`, `member_id`, `bow_type`, `distance`, `window_start`, `window_end`, `status`, `eval_seq`, `reviewed_by`, `reviewed_at`, `review_note`, `valid_from`, `valid_until`, `withdrawn_by`, `withdrawn_at`, `withdraw_reason`, `created_by`, `created_at`) VALUES (1, 'C20260920001', 1, 'RECURVE', 50, '2026-09-19 00:00:00', '2026-09-19 23:59:59', 'APPROVED', 1, '李慕白', '2026-09-20 10:05:00', '证据达标，复核通过', '2026-09-20 10:05:00', '2027-09-20 10:05:00', NULL, NULL, NULL, '李慕白', '2026-09-20 09:40:00');
INSERT INTO cert_application (`id`, `app_no`, `member_id`, `bow_type`, `distance`, `window_start`, `window_end`, `status`, `eval_seq`, `reviewed_by`, `reviewed_at`, `review_note`, `valid_from`, `valid_until`, `withdrawn_by`, `withdrawn_at`, `withdraw_reason`, `created_by`, `created_at`) VALUES (2, 'C20250911001', 2, 'TRADITIONAL', 18, '2025-09-10 00:00:00', '2025-09-10 23:59:59', 'APPROVED', 1, '苏禾', '2025-09-11 11:00:00', '历史认证，到期需重新评定', '2025-09-11 11:00:00', '2026-09-11 11:00:00', NULL, NULL, NULL, '苏禾', '2025-09-11 10:30:00');
INSERT INTO cert_application (`id`, `app_no`, `member_id`, `bow_type`, `distance`, `window_start`, `window_end`, `status`, `eval_seq`, `reviewed_by`, `reviewed_at`, `review_note`, `valid_from`, `valid_until`, `withdrawn_by`, `withdrawn_at`, `withdraw_reason`, `created_by`, `created_at`) VALUES (3, 'C20260917001', 4, 'COMPOUND', 30, '2026-09-16 00:00:00', '2026-09-16 23:59:59', 'WITHDRAWN', 1, '王铮', '2026-09-17 09:30:00', '证据达标，复核通过', '2026-09-17 09:30:00', '2027-09-17 09:30:00', '王铮', '2026-09-25 15:00:00', '抽查发现证据窗口与实际训练记录不符，撤回并要求重新评定', '王铮', '2026-09-17 09:00:00');

-- cert_eval：每笔申请一次评定，规则快照随评定固化
INSERT INTO cert_eval (`id`, `application_id`, `eval_seq`, `rule_id`, `rule_code`, `rule_version`, `snap_group_size`, `snap_min_rounds`, `snap_min_average`, `snap_validity_months`, `window_start`, `window_end`, `round_count`, `arrow_total`, `total_score`, `avg_score`, `pass_flag`, `eval_message`, `created_by`, `created_at`) VALUES (1, 1, 1, 3, 'RECURVE-50', 1, 6, 1, 7.50, 12, '2026-09-19 00:00:00', '2026-09-19 23:59:59', 1, 6, 58, 9.67, 1, '证据窗口内 1 个回合共 6 支箭，总环 58，平均 9.67 环/支；通过标准：≥1 回合且平均 ≥7.50，系统预评达标', '李慕白', '2026-09-20 09:40:00');
INSERT INTO cert_eval (`id`, `application_id`, `eval_seq`, `rule_id`, `rule_code`, `rule_version`, `snap_group_size`, `snap_min_rounds`, `snap_min_average`, `snap_validity_months`, `window_start`, `window_end`, `round_count`, `arrow_total`, `total_score`, `avg_score`, `pass_flag`, `eval_message`, `created_by`, `created_at`) VALUES (2, 2, 1, 6, 'TRADITIONAL-18', 1, 6, 1, 6.00, 12, '2025-09-10 00:00:00', '2025-09-10 23:59:59', 1, 6, 40, 6.67, 1, '证据窗口内 1 个回合共 6 支箭，总环 40，平均 6.67 环/支；通过标准：≥1 回合且平均 ≥6.00，系统预评达标', '苏禾', '2025-09-11 10:30:00');
INSERT INTO cert_eval (`id`, `application_id`, `eval_seq`, `rule_id`, `rule_code`, `rule_version`, `snap_group_size`, `snap_min_rounds`, `snap_min_average`, `snap_validity_months`, `window_start`, `window_end`, `round_count`, `arrow_total`, `total_score`, `avg_score`, `pass_flag`, `eval_message`, `created_by`, `created_at`) VALUES (3, 3, 1, 5, 'COMPOUND-30', 1, 12, 1, 8.00, 12, '2026-09-16 00:00:00', '2026-09-16 23:59:59', 1, 12, 112, 9.33, 1, '证据窗口内 1 个回合共 12 支箭，总环 112，平均 9.33 环/支；通过标准：≥1 回合且平均 ≥8.00，系统预评达标', '王铮', '2026-09-17 09:00:00');

-- cert_eval_round：三笔评定分别采信回合 6 / 13 / 3
INSERT INTO cert_eval_round (`id`, `eval_id`, `round_id`) VALUES (1, 1, 6);
INSERT INTO cert_eval_round (`id`, `eval_id`, `round_id`) VALUES (2, 2, 13);
INSERT INTO cert_eval_round (`id`, `eval_id`, `round_id`) VALUES (3, 3, 3);

-- cert_log：建立 / 系统预评 / 复核结论全程留痕
INSERT INTO cert_log (`id`, `application_id`, `action`, `operator`, `role`, `detail`, `created_at`) VALUES (1, 1, 'CREATE', '李慕白', 'COACH', '建立认证申请：陈子昂 · 反曲弓 50 米 · 规则 RECURVE-50 v1；证据窗口 2026-09-19 00:00 ~ 2026-09-19 23:59', '2026-09-20 09:40:00');
INSERT INTO cert_log (`id`, `application_id`, `action`, `operator`, `role`, `detail`, `created_at`) VALUES (2, 1, 'EVAL', '认证系统', 'SYSTEM', '系统预评达标：1 回合 6 支箭平均 9.67 环（标准 ≥7.50），提交复核', '2026-09-20 09:40:00');
INSERT INTO cert_log (`id`, `application_id`, `action`, `operator`, `role`, `detail`, `created_at`) VALUES (3, 1, 'APPROVE', '李慕白', 'COACH', '复核通过：认证 2026-09-20 生效，有效期至 2027-09-20；适用范围：反曲弓 50 米及以内射距', '2026-09-20 10:05:00');
INSERT INTO cert_log (`id`, `application_id`, `action`, `operator`, `role`, `detail`, `created_at`) VALUES (4, 2, 'CREATE', '苏禾', 'COACH', '建立认证申请：林悦 · 传统弓 18 米 · 规则 TRADITIONAL-18 v1；证据窗口 2025-09-10 00:00 ~ 2025-09-10 23:59', '2025-09-11 10:30:00');
INSERT INTO cert_log (`id`, `application_id`, `action`, `operator`, `role`, `detail`, `created_at`) VALUES (5, 2, 'EVAL', '认证系统', 'SYSTEM', '系统预评达标：1 回合 6 支箭平均 6.67 环（标准 ≥6.00），提交复核', '2025-09-11 10:30:00');
INSERT INTO cert_log (`id`, `application_id`, `action`, `operator`, `role`, `detail`, `created_at`) VALUES (6, 2, 'APPROVE', '苏禾', 'COACH', '复核通过：认证 2025-09-11 生效，有效期至 2026-09-11；适用范围：传统弓 18 米及以内射距', '2025-09-11 11:00:00');
INSERT INTO cert_log (`id`, `application_id`, `action`, `operator`, `role`, `detail`, `created_at`) VALUES (7, 3, 'CREATE', '王铮', 'COACH', '建立认证申请：孙嘉 · 复合弓 30 米 · 规则 COMPOUND-30 v1；证据窗口 2026-09-16 00:00 ~ 2026-09-16 23:59', '2026-09-17 09:00:00');
INSERT INTO cert_log (`id`, `application_id`, `action`, `operator`, `role`, `detail`, `created_at`) VALUES (8, 3, 'EVAL', '认证系统', 'SYSTEM', '系统预评达标：1 回合 12 支箭平均 9.33 环（标准 ≥8.00），提交复核', '2026-09-17 09:00:00');
INSERT INTO cert_log (`id`, `application_id`, `action`, `operator`, `role`, `detail`, `created_at`) VALUES (9, 3, 'APPROVE', '王铮', 'COACH', '复核通过：认证 2026-09-17 生效，有效期至 2027-09-17；适用范围：复合弓 30 米及以内射距', '2026-09-17 09:30:00');
-- ================= 模块十：器材损伤清算 =================
-- 损伤计价规则（按器材类型 + 严重程度，带生效日期）：
-- 同一（类型, 严重度）可有不同生效日期的版本；登记损伤时取「生效日期 <= 检查开始时间」的最新一条，
-- 检查开始后即使发布新价格 / 新版本，本单仍使用已固化到损伤项上的快照，历史金额不随后续调价改变。
CREATE TABLE IF NOT EXISTS damage_pricing_rule (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  equip_type     VARCHAR(16)  NOT NULL COMMENT 'RECURVE / COMPOUND / TRADITIONAL / GEAR / ARROW',
  severity       VARCHAR(16)  NOT NULL COMMENT 'LIGHT 轻微 / MEDIUM 中等 / SEVERE 严重',
  effective_date DATE         NOT NULL COMMENT '生效日期',
  fee            DECIMAL(10,2) NOT NULL COMMENT '损伤费用（元）',
  repair_flag    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否送修',
  scrap_flag     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否报废封存',
  status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 现行 / RETIRED 已停用',
  note           VARCHAR(200) NULL COMMENT '规则说明',
  created_by     VARCHAR(32)  NOT NULL COMMENT '维护人（值班经理）',
  created_at     DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_damage_rule (equip_type, severity, effective_date),
  KEY idx_damage_rule_lookup (equip_type, severity, status, effective_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 清算单：器材管理员从正在租借的器材发起检查，绑定检查开始时的四条基线：
-- 租借人（id/姓名/租出时间）、器材状态、计价规则（通过损伤项快照间接固化）、会员余额基线。
-- 结清前任一基线变化 → RECHECK 待重新核对，核对（rebind）后才能继续结清。
-- 状态机：INSPECTING 检查中 → PENDING_SETTLE 待结清 → SETTLED 已结清；中途基线漂移转 RECHECK。
CREATE TABLE IF NOT EXISTS damage_settlement (
  id                     BIGINT       NOT NULL AUTO_INCREMENT,
  settle_no              VARCHAR(24)  NOT NULL COMMENT '清算单号',
  equipment_id           BIGINT       NOT NULL,
  equip_code             VARCHAR(24)  NOT NULL COMMENT '器材编号快照',
  equip_type             VARCHAR(16)  NOT NULL COMMENT '器材类型快照',
  member_id              BIGINT       NOT NULL COMMENT '检查开始时的租借人',
  member_name            VARCHAR(32)  NOT NULL COMMENT '租借人姓名快照',
  status                 VARCHAR(16)  NOT NULL COMMENT 'INSPECTING / PENDING_SETTLE / RECHECK / SETTLED',
  -- 检查开始时的基线
  baseline_equip_status  VARCHAR(16)  NOT NULL COMMENT '器材状态基线（恒为 RENTED）',
  baseline_renter_id     BIGINT       NOT NULL COMMENT '租借人基线 id',
  baseline_renter_name   VARCHAR(32)  NOT NULL,
  baseline_rented_at     DATETIME     NULL COMMENT '租出时间基线',
  baseline_balance       DECIMAL(10,2) NOT NULL COMMENT '会员余额基线',
  -- 结清结果
  final_disposal         VARCHAR(16)  NULL COMMENT 'NONE 回库 / REPAIR 送修 / SCRAP 报废封存',
  total_fee              DECIMAL(10,2) NULL COMMENT '减免 / 异议处理后的应收总额',
  balance_deducted       DECIMAL(10,2) NULL COMMENT '本次实际从余额扣减（最多扣到 0）',
  debt_amount            DECIMAL(10,2) NULL COMMENT '余额不足形成的待缴费用',
  inspector              VARCHAR(32)  NOT NULL COMMENT '发起检查的器材管理员',
  created_at             DATETIME     NOT NULL,
  settled_by             VARCHAR(32)  NULL,
  settled_at             DATETIME     NULL,
  settle_note            VARCHAR(500) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_settle_no (settle_no),
  KEY idx_settle_equip (equipment_id, status),
  KEY idx_settle_member (member_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 损伤明细：器材管理员逐项登记部位 / 严重程度 / 现场说明 / 责任会员。
-- 登记瞬间固化计价规则快照（snap_*），后续调价 / 停用规则不影响本项金额。
-- 减免与异议只能改 final_fee / 状态列，部位 / 严重度 / 说明 / 责任会员 / 规则快照永不被改写。
CREATE TABLE IF NOT EXISTS damage_settlement_item (
  id                   BIGINT       NOT NULL AUTO_INCREMENT,
  settlement_id        BIGINT       NOT NULL,
  item_seq             INT          NOT NULL COMMENT '单内损伤项序号，从 1 开始',
  part                 VARCHAR(64)  NOT NULL COMMENT '损伤部位（弓片 / 弓弦 / 箭杆 / 护具等）',
  severity             VARCHAR(16)  NOT NULL COMMENT 'LIGHT / MEDIUM / SEVERE',
  description          VARCHAR(500) NOT NULL COMMENT '现场说明',
  responsible_id       BIGINT       NOT NULL COMMENT '责任会员 id',
  responsible_name     VARCHAR(32)  NOT NULL COMMENT '责任会员姓名',
  -- 规则快照
  rule_id              BIGINT       NOT NULL COMMENT '采用的计价规则版本 id',
  snap_rule_code       VARCHAR(40)  NOT NULL COMMENT '类型-严重度快照标识',
  snap_effective_date  DATE         NOT NULL,
  snap_fee             DECIMAL(10,2) NOT NULL COMMENT '快照费用（管理员原检查金额）',
  snap_repair_flag     TINYINT(1)   NOT NULL COMMENT '快照：是否送修',
  snap_scrap_flag      TINYINT(1)   NOT NULL COMMENT '快照：是否报废封存',
  -- 金额与处理
  final_fee            DECIMAL(10,2) NOT NULL COMMENT '当前应收金额：原检查费经减免 / 异议后的结果',
  waived_flag          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '经理是否已减免（与异议减免互斥路径）',
  waived_by            VARCHAR(32)  NULL,
  waived_at            DATETIME     NULL,
  waived_reason        VARCHAR(500) NULL,
  -- 异议：NULL 未异议；DISPUTING 处理中（金额冻结）；MAINTAIN / REDUCE / REVOKE 为处理结论
  dispute_status       VARCHAR(16)  NULL,
  dispute_reason       VARCHAR(500) NULL,
  disputed_at          DATETIME     NULL,
  dispute_handled_by   VARCHAR(32)  NULL,
  dispute_handled_at   DATETIME     NULL,
  dispute_note         VARCHAR(500) NULL,
  created_at           DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_settle_item_seq (settlement_id, item_seq),
  KEY idx_damage_item_settle (settlement_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 待缴费用：结清时余额不足，扣到 0 后剩余部分落本表，同时暂停该会员租借资格。
-- 会员补足后由补缴解限流程核销（可分多次补缴），全部缴清恢复租借并留下补款轨迹。
CREATE TABLE IF NOT EXISTS member_debt (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  debt_no         VARCHAR(24)  NOT NULL,
  settlement_id   BIGINT       NOT NULL,
  member_id       BIGINT       NOT NULL,
  original_amount DECIMAL(10,2) NOT NULL COMMENT '形成时的待缴金额',
  paid_amount     DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '已补缴金额',
  amount          DECIMAL(10,2) NOT NULL COMMENT '当前剩余待缴（= original - paid - 撤销减免）',
  status          VARCHAR(16)  NOT NULL COMMENT 'UNPAID 待缴 / PAID 已缴清 / REVOKED 已撤销',
  created_at      DATETIME     NOT NULL,
  paid_at         DATETIME     NULL COMMENT '缴清时间',
  settle_no       VARCHAR(24)  NOT NULL COMMENT '来源清算单号（展示用快照）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_debt_no (debt_no),
  KEY idx_debt_member (member_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 补缴 / 撤销轨迹：欠款的每一次补缴与异议撤销都在此留痕，会员与经理均可查
CREATE TABLE IF NOT EXISTS member_debt_payment (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  debt_id     BIGINT       NOT NULL,
  member_id   BIGINT       NOT NULL,
  amount      DECIMAL(10,2) NOT NULL COMMENT '本次补缴 / 撤销金额',
  kind        VARCHAR(16)  NOT NULL COMMENT 'REPAY 补缴 / REVOKE 异议撤销',
  operator    VARCHAR(32)  NOT NULL,
  detail      VARCHAR(500) NULL,
  created_at  DATETIME     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_debt_pay_debt (debt_id),
  KEY idx_debt_pay_member (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 清算操作轨迹：发起检查 / 登记 / 减免 / 异议 / 基线变化 / 核对 / 结清 / 补缴 / 放弃，全程留痕
CREATE TABLE IF NOT EXISTS damage_settlement_log (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  settlement_id BIGINT       NOT NULL,
  action        VARCHAR(24)  NOT NULL,
  operator      VARCHAR(32)  NOT NULL,
  role          VARCHAR(16)  NOT NULL COMMENT 'KEEPER 器材管理员 / MANAGER 值班经理 / MEMBER 会员 / SYSTEM 清算系统',
  detail        VARCHAR(500) NULL,
  created_at    DATETIME     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_settle_log (settlement_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- damage_pricing_rule 种子：5 类器材 × 3 档严重度。
-- 轻微：仅收检修费器材回库；中等：收费并送修；严重：收费并报废永久封存。
INSERT INTO damage_pricing_rule (`id`, `equip_type`, `severity`, `effective_date`, `fee`, `repair_flag`, `scrap_flag`, `status`, `note`, `created_by`, `created_at`) VALUES (1, 'RECURVE', 'LIGHT', '2026-09-01', 80.00, 0, 0, 'ACTIVE', '反曲弓轻微损伤（如弓片浅表划痕）：收取检修费，器材回库', '值班经理', '2026-09-01 09:00:00');
INSERT INTO damage_pricing_rule (`id`, `equip_type`, `severity`, `effective_date`, `fee`, `repair_flag`, `scrap_flag`, `status`, `note`, `created_by`, `created_at`) VALUES (2, 'RECURVE', 'MEDIUM', '2026-09-01', 300.00, 1, 0, 'ACTIVE', '反曲弓中等损伤（如弓弦起毛、弓片暗裂）：收费并送修', '值班经理', '2026-09-01 09:00:00');
INSERT INTO damage_pricing_rule (`id`, `equip_type`, `severity`, `effective_date`, `fee`, `repair_flag`, `scrap_flag`, `status`, `note`, `created_by`, `created_at`) VALUES (3, 'RECURVE', 'SEVERE', '2026-09-01', 1200.00, 0, 1, 'ACTIVE', '反曲弓严重损伤（如弓片贯穿裂纹）：收费并报废封存', '值班经理', '2026-09-01 09:00:00');
INSERT INTO damage_pricing_rule (`id`, `equip_type`, `severity`, `effective_date`, `fee`, `repair_flag`, `scrap_flag`, `status`, `note`, `created_by`, `created_at`) VALUES (4, 'COMPOUND', 'LIGHT', '2026-09-01', 120.00, 0, 0, 'ACTIVE', '复合弓轻微损伤：收取检修费，器材回库', '值班经理', '2026-09-01 09:00:00');
INSERT INTO damage_pricing_rule (`id`, `equip_type`, `severity`, `effective_date`, `fee`, `repair_flag`, `scrap_flag`, `status`, `note`, `created_by`, `created_at`) VALUES (5, 'COMPOUND', 'MEDIUM', '2026-09-01', 500.00, 1, 0, 'ACTIVE', '复合弓中等损伤（如轮组磨损、弓弦磨损）：收费并送修', '值班经理', '2026-09-01 09:00:00');
INSERT INTO damage_pricing_rule (`id`, `equip_type`, `severity`, `effective_date`, `fee`, `repair_flag`, `scrap_flag`, `status`, `note`, `created_by`, `created_at`) VALUES (6, 'COMPOUND', 'SEVERE', '2026-09-01', 2000.00, 0, 1, 'ACTIVE', '复合弓严重损伤（如弓片裂纹 / 轮组断裂）：收费并报废封存', '值班经理', '2026-09-01 09:00:00');
INSERT INTO damage_pricing_rule (`id`, `equip_type`, `severity`, `effective_date`, `fee`, `repair_flag`, `scrap_flag`, `status`, `note`, `created_by`, `created_at`) VALUES (7, 'TRADITIONAL', 'LIGHT', '2026-09-01', 60.00, 0, 0, 'ACTIVE', '传统弓轻微损伤：收取检修费，器材回库', '值班经理', '2026-09-01 09:00:00');
INSERT INTO damage_pricing_rule (`id`, `equip_type`, `severity`, `effective_date`, `fee`, `repair_flag`, `scrap_flag`, `status`, `note`, `created_by`, `created_at`) VALUES (8, 'TRADITIONAL', 'MEDIUM', '2026-09-01', 200.00, 1, 0, 'ACTIVE', '传统弓中等损伤：收费并送修', '值班经理', '2026-09-01 09:00:00');
INSERT INTO damage_pricing_rule (`id`, `equip_type`, `severity`, `effective_date`, `fee`, `repair_flag`, `scrap_flag`, `status`, `note`, `created_by`, `created_at`) VALUES (9, 'TRADITIONAL', 'SEVERE', '2026-09-01', 800.00, 0, 1, 'ACTIVE', '传统弓严重损伤：收费并报废封存', '值班经理', '2026-09-01 09:00:00');
INSERT INTO damage_pricing_rule (`id`, `equip_type`, `severity`, `effective_date`, `fee`, `repair_flag`, `scrap_flag`, `status`, `note`, `created_by`, `created_at`) VALUES (10, 'GEAR', 'LIGHT', '2026-09-01', 30.00, 0, 0, 'ACTIVE', '护具轻微损伤：收取检修费，器材回库', '值班经理', '2026-09-01 09:00:00');
INSERT INTO damage_pricing_rule (`id`, `equip_type`, `severity`, `effective_date`, `fee`, `repair_flag`, `scrap_flag`, `status`, `note`, `created_by`, `created_at`) VALUES (11, 'GEAR', 'MEDIUM', '2026-09-01', 120.00, 1, 0, 'ACTIVE', '护具中等损伤（如护臂开裂 / 护弦松脱）：收费并送修', '值班经理', '2026-09-01 09:00:00');
INSERT INTO damage_pricing_rule (`id`, `equip_type`, `severity`, `effective_date`, `fee`, `repair_flag`, `scrap_flag`, `status`, `note`, `created_by`, `created_at`) VALUES (12, 'GEAR', 'SEVERE', '2026-09-01', 400.00, 0, 1, 'ACTIVE', '护具严重破损失去防护能力：收费并报废封存', '值班经理', '2026-09-01 09:00:00');
INSERT INTO damage_pricing_rule (`id`, `equip_type`, `severity`, `effective_date`, `fee`, `repair_flag`, `scrap_flag`, `status`, `note`, `created_by`, `created_at`) VALUES (13, 'ARROW', 'LIGHT', '2026-09-01', 10.00, 0, 0, 'ACTIVE', '箭支轻微损伤（如羽片轻微卷折）：收费，器材回库', '值班经理', '2026-09-01 09:00:00');
INSERT INTO damage_pricing_rule (`id`, `equip_type`, `severity`, `effective_date`, `fee`, `repair_flag`, `scrap_flag`, `status`, `note`, `created_by`, `created_at`) VALUES (14, 'ARROW', 'MEDIUM', '2026-09-01', 40.00, 1, 0, 'ACTIVE', '箭杆弯曲可校正：收费并送修', '值班经理', '2026-09-01 09:00:00');
INSERT INTO damage_pricing_rule (`id`, `equip_type`, `severity`, `effective_date`, `fee`, `repair_flag`, `scrap_flag`, `status`, `note`, `created_by`, `created_at`) VALUES (15, 'ARROW', 'SEVERE', '2026-09-01', 120.00, 0, 1, 'ACTIVE', '箭杆断裂 / 箭头松动无法修复：收费并报废封存', '值班经理', '2026-09-01 09:00:00');

INSERT INTO cert_log (`id`, `application_id`, `action`, `operator`, `role`, `detail`, `created_at`) VALUES (10, 3, 'WITHDRAW', '王铮', 'COACH', '撤回认证：抽查发现证据窗口与实际训练记录不符，撤回并要求重新评定', '2026-09-25 15:00:00');

-- ================= 模块十一：未成年会员监护授权台 =================
-- 监护授权链：每个未成年会员一条链，链上串起不可变的授权版本。
-- 链状态 DRAFT 草稿 → PENDING_CONFIRM 待监护人确认 → VALID 有效 ⇄ SUSPENDED 暂停 → REVOKED 撤销。
-- 监护人确认后才能有效；范围（课程 / 箭道 / 器材 / 接管人）或生效日、终止日任一变化都必须发布新版本重新确认，
-- 旧版本转 SUPERSEDED，仍可查询但不得继续放行。
CREATE TABLE IF NOT EXISTS guardian_consent (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  consent_no       VARCHAR(24)  NOT NULL COMMENT '授权单号',
  member_id        BIGINT       NOT NULL COMMENT '未成年会员',
  guardian_name    VARCHAR(32)  NOT NULL COMMENT '监护人姓名',
  guardian_phone   VARCHAR(20)  NOT NULL COMMENT '监护人手机号',
  guardian_relation VARCHAR(16) NOT NULL COMMENT '与未成年人关系：FATHER 父 / MOTHER 母 / OTHER 其他法定监护人',
  status           VARCHAR(20)  NOT NULL COMMENT 'DRAFT / PENDING_CONFIRM / VALID / SUSPENDED / REVOKED',
  current_version_id BIGINT     NULL COMMENT '当前版本 id（草稿时为草稿版本；有效 / 暂停时为现行版本）',
  emergency_contact_name  VARCHAR(32)  NOT NULL COMMENT '紧急联系人姓名',
  emergency_contact_phone VARCHAR(20)  NOT NULL COMMENT '紧急联系人电话',
  remark           VARCHAR(500) NULL COMMENT '必要备注（过敏史 / 特殊照护等）',
  created_by       VARCHAR(32)  NOT NULL COMMENT '建立授权链的前台',
  created_at       DATETIME     NOT NULL,
  updated_at       DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_consent_no (consent_no),
  KEY idx_consent_member (member_id),
  CONSTRAINT fk_consent_member FOREIGN KEY (member_id) REFERENCES members (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 授权版本：内容（生效日 / 终止日 / 课程 / 箭道 / 器材 / 接管人 / 备注）一经建立即冻结不可改写；
-- 监护人确认是版本生效的唯一入口。状态 DRAFT / PENDING_CONFIRM / VALID / SUPERSEDED / SUSPENDED / REVOKED / REJECTED。
-- 新版本发布时，被取代的旧 VALID 版本原子转 SUPERSEDED（连同链上当前版本指针一起切换），
-- 已经把旧版本钉为现场基线的到馆交接不受影响（基线不可篡改），但不能再凭旧版本放行新动作。
CREATE TABLE IF NOT EXISTS guardian_consent_version (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  consent_id       BIGINT       NOT NULL,
  version_no       INT          NOT NULL COMMENT '版本号，从 1 递增',
  status           VARCHAR(20)  NOT NULL COMMENT 'DRAFT / PENDING_CONFIRM / VALID / SUPERSEDED / SUSPENDED / REVOKED / REJECTED',
  effective_from   DATE         NOT NULL COMMENT '生效日',
  effective_until  DATE         NOT NULL COMMENT '终止日',
  version_remark   VARCHAR(500) NULL COMMENT '本版本必要备注',
  -- 监护人确认
  confirmed_by     VARCHAR(32)  NULL COMMENT '确认监护人（现场登记的确认人姓名）',
  confirmed_at     DATETIME     NULL,
  confirm_relation VARCHAR(16)  NULL COMMENT '确认人身份关系（与 guardian_relation 核对）',
  confirm_note     VARCHAR(500) NULL,
  -- 拒绝 / 暂停 / 撤销 / 取代的留痕
  rejected_by      VARCHAR(32)  NULL,
  rejected_at      DATETIME     NULL,
  reject_reason    VARCHAR(500) NULL,
  superseded_by_version_id BIGINT NULL COMMENT '由哪个新版本取代',
  superseded_at    DATETIME     NULL,
  suspended_by     VARCHAR(32)  NULL,
  suspended_at     DATETIME     NULL,
  suspend_reason   VARCHAR(500) NULL,
  revoked_by       VARCHAR(32)  NULL,
  revoked_at       DATETIME     NULL,
  revoke_reason    VARCHAR(500) NULL,
  -- 内容指纹：确认 / 接管 / 放行时比对，防止「同版本号但内容已变」
  content_fingerprint VARCHAR(64) NOT NULL,
  created_by       VARCHAR(32)  NOT NULL,
  created_at       DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_consent_version_no (consent_id, version_no),
  KEY idx_cv_consent (consent_id, status),
  CONSTRAINT fk_cv_consent FOREIGN KEY (consent_id) REFERENCES guardian_consent (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 版本-课程范围（一组课程作为整体校验：任一项不覆盖则整体不放行）
CREATE TABLE IF NOT EXISTS guardian_consent_course (
  id         BIGINT NOT NULL AUTO_INCREMENT,
  version_id BIGINT NOT NULL,
  course_id  BIGINT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_gc_course (version_id, course_id),
  KEY idx_gc_course_course (course_id),
  CONSTRAINT fk_gc_course_version FOREIGN KEY (version_id) REFERENCES guardian_consent_version (id),
  CONSTRAINT fk_gc_course_course FOREIGN KEY (course_id) REFERENCES courses (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 版本-箭道范围
CREATE TABLE IF NOT EXISTS guardian_consent_lane (
  id         BIGINT NOT NULL AUTO_INCREMENT,
  version_id BIGINT NOT NULL,
  lane_id    BIGINT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_gc_lane (version_id, lane_id),
  KEY idx_gc_lane_lane (lane_id),
  CONSTRAINT fk_gc_lane_version FOREIGN KEY (version_id) REFERENCES guardian_consent_version (id),
  CONSTRAINT fk_gc_lane_lane FOREIGN KEY (lane_id) REFERENCES lanes (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 版本-器材范围
CREATE TABLE IF NOT EXISTS guardian_consent_equipment (
  id           BIGINT NOT NULL AUTO_INCREMENT,
  version_id   BIGINT NOT NULL,
  equipment_id BIGINT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_gc_equip (version_id, equipment_id),
  KEY idx_gc_equip_equip (equipment_id),
  CONSTRAINT fk_gc_equip_version FOREIGN KEY (version_id) REFERENCES guardian_consent_version (id),
  CONSTRAINT fk_gc_equip_equip FOREIGN KEY (equipment_id) REFERENCES equipment (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 版本-允许承担现场接管责任的人员（接送人名单；实际接管人必须在名单内）
CREATE TABLE IF NOT EXISTS guardian_consent_pickup (
  id         BIGINT       NOT NULL AUTO_INCREMENT,
  version_id BIGINT       NOT NULL,
  person_name VARCHAR(32)  NOT NULL COMMENT '接管人姓名',
  id_no      VARCHAR(32)  NOT NULL COMMENT '证件号（身份证后四位等现场核验标识）',
  phone      VARCHAR(20)  NOT NULL COMMENT '接管人手机号',
  relation   VARCHAR(16)  NOT NULL COMMENT '与未成年人关系：FATHER / MOTHER / GRANDPARENT / OTHER',
  PRIMARY KEY (id),
  UNIQUE KEY uk_gc_pickup (version_id, person_name, id_no),
  CONSTRAINT fk_gc_pickup_version FOREIGN KEY (version_id) REFERENCES guardian_consent_version (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 每次到馆的现场交接记录（独立单据）：阶段 PENDING_PICKUP 待接管 → TRAINING 训练中
-- → PENDING_RETURN 待交还 → RETURNED 已交还；未实际接管的单据可 CANCELED 取消。
-- 接管时把当时的有效授权版本钉为现场基线 baseline_version_id；基线此后变化（新版本 / 暂停 / 撤销）
-- 不改写已发生事实，只把冻结位置 1：尚未开始的课程 / 箭道 / 器材动作立即冻结，需工作人员按新基线重新核对。
CREATE TABLE IF NOT EXISTS minor_visit (
  id                BIGINT       NOT NULL AUTO_INCREMENT,
  visit_no          VARCHAR(24)  NOT NULL COMMENT '交接单号',
  member_id         BIGINT       NOT NULL COMMENT '未成年会员',
  status            VARCHAR(20)  NOT NULL COMMENT 'PENDING_PICKUP / TRAINING / PENDING_RETURN / RETURNED / CANCELED',
  -- 现场基线
  consent_id        BIGINT       NOT NULL COMMENT '接管时核对的授权链',
  baseline_version_id BIGINT     NOT NULL COMMENT '接管时钉住的授权版本（历史事实，不随后续换版改写）',
  baseline_fingerprint VARCHAR(64) NOT NULL COMMENT '接管时版本内容指纹',
  baseline_frozen   TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '基线是否已漂移：1 已冻结未开始动作，需重新核对',
  freeze_reason     VARCHAR(500) NULL,
  frozen_at         DATETIME     NULL,
  -- 实际接管人（接管时核验证件，必须在基线版本接管人名单内）
  pickup_person_name VARCHAR(32) NULL,
  pickup_person_idno VARCHAR(32) NULL,
  pickup_person_phone VARCHAR(20) NULL,
  pickup_person_relation VARCHAR(16) NULL,
  pickup_operator   VARCHAR(32)  NULL COMMENT '办理接管的前台',
  pickup_at         DATETIME     NULL,
  -- 训练开始（课程签到放行）
  start_operator    VARCHAR(32)  NULL COMMENT '训练开始放行的教练',
  started_at        DATETIME     NULL,
  -- 交还：必须由合格接管人完成；managerOverride=1 为值班经理异常处置（必须填写原因）
  return_person_name VARCHAR(32) NULL,
  return_person_idno VARCHAR(32) NULL,
  return_person_phone VARCHAR(20) NULL,
  return_person_relation VARCHAR(16) NULL,
  qualified_pickup  TINYINT(1)   NULL COMMENT '交还人是否在基线版本接管人名单内',
  manager_override  TINYINT(1)   NOT NULL DEFAULT 0,
  override_manager  VARCHAR(32)  NULL,
  override_reason   VARCHAR(500) NULL,
  return_operator   VARCHAR(32)  NULL COMMENT '办理交还的前台',
  returned_at       DATETIME     NULL,
  -- 取消（仅待接管可取消）
  cancel_operator   VARCHAR(32)  NULL,
  canceled_at       DATETIME     NULL,
  cancel_reason     VARCHAR(500) NULL,
  created_by        VARCHAR(32)  NOT NULL,
  created_at        DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_visit_no (visit_no),
  KEY idx_visit_member_status (member_id, status),
  KEY idx_visit_open (member_id, status),
  CONSTRAINT fk_visit_member FOREIGN KEY (member_id) REFERENCES members (id),
  CONSTRAINT fk_visit_consent FOREIGN KEY (consent_id) REFERENCES guardian_consent (id),
  CONSTRAINT fk_visit_version FOREIGN KEY (baseline_version_id) REFERENCES guardian_consent_version (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 交接单-资源放行台账：接管时声明本次要用的课程 / 箭道 / 器材（一组整体校验），
-- 训练开始前逐项放行；任一不覆盖则整组拒绝，绝不留下部分放行。
-- status PENDING 待放行 → RELEASED 已放行 → FROZEN 基线漂移冻结；REJECTED 为整组校验失败登记。
CREATE TABLE IF NOT EXISTS minor_visit_resource (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  visit_id    BIGINT       NOT NULL,
  resource_type VARCHAR(16) NOT NULL COMMENT 'COURSE 课程 / LANE 箭道 / EQUIPMENT 器材',
  resource_id BIGINT       NOT NULL,
  resource_label VARCHAR(64) NOT NULL COMMENT '资源名称快照（课程名 / 道号 / 器材编号）',
  status      VARCHAR(16)  NOT NULL COMMENT 'PENDING / RELEASED / FROZEN / REJECTED',
  release_operator VARCHAR(32) NULL,
  released_at DATETIME     NULL,
  reject_reason VARCHAR(500) NULL,
  created_at  DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_visit_resource (visit_id, resource_type, resource_id),
  KEY idx_visit_resource (visit_id, status),
  CONSTRAINT fk_vr_visit FOREIGN KEY (visit_id) REFERENCES minor_visit (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 授权链 / 版本状态轨迹：建立 / 提交确认 / 监护人确认 / 拒绝 / 发新版本（旧版取代）/ 暂停 / 恢复 / 撤销，全程留痕。
-- before_status / after_status 与 version_no 记录变化前后的授权基线；detail 留存原因与范围摘要。
CREATE TABLE IF NOT EXISTS guardian_consent_log (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  consent_id   BIGINT       NOT NULL,
  version_id   BIGINT       NULL COMMENT '相关版本（纯链级动作可为空）',
  version_no   INT          NULL,
  action       VARCHAR(24)  NOT NULL COMMENT 'CREATE / SUBMIT / CONFIRM / REJECT / RECALL / NEW_VERSION / SUPERSEDE / SUSPEND / RESUME / REVOKE',
  before_status VARCHAR(20) NULL,
  after_status  VARCHAR(20) NULL,
  operator     VARCHAR(32)  NOT NULL,
  role         VARCHAR(16)  NOT NULL COMMENT 'FRONT 前台 / GUARDIAN 监护人 / MANAGER 值班经理 / SYSTEM 授权系统',
  detail       VARCHAR(500) NULL,
  created_at   DATETIME     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_gcl_consent (consent_id),
  CONSTRAINT fk_gcl_consent FOREIGN KEY (consent_id) REFERENCES guardian_consent (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 现场交接轨迹：建单 / 接管 / 整组放行 / 拦截 / 基线冻结 / 重新核对 / 异常处置 / 交还 / 取消，全程留痕。
CREATE TABLE IF NOT EXISTS minor_visit_log (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  visit_id    BIGINT       NOT NULL,
  action      VARCHAR(24)  NOT NULL COMMENT 'CREATE / PICKUP / RELEASE / BLOCK / FREEZE / RECHECK / EXCEPTION / RETURN / CANCEL',
  stage       VARCHAR(16)  NULL COMMENT '动作发生时交接阶段',
  operator    VARCHAR(32)  NOT NULL,
  role        VARCHAR(16)  NOT NULL COMMENT 'FRONT 前台 / COACH 教练 / KEEPER 器材管理员 / MANAGER 值班经理 / GUARDIAN 监护人 / SYSTEM 授权系统',
  detail      VARCHAR(500) NULL,
  created_at  DATETIME     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_mvl_visit (visit_id),
  CONSTRAINT fk_mvl_visit FOREIGN KEY (visit_id) REFERENCES minor_visit (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 未成年会员种子：许静（id=10）、唐宁（id=12）标记为未成年并补出生日期；吴桐（id=6）为待确认授权的未成年会员
UPDATE members SET minor_flag = 1, birth_date = '2011-06-15' WHERE id = 10;
UPDATE members SET minor_flag = 1, birth_date = '2012-11-08' WHERE id = 12;
UPDATE members SET minor_flag = 1, birth_date = '2010-09-01' WHERE id = 6;

-- 许静（id=10）：授权链 1，v2 已确认为当前有效版本（v1 已被取代，可查但不放行）
INSERT INTO guardian_consent (`id`, `consent_no`, `member_id`, `guardian_name`, `guardian_phone`, `guardian_relation`, `status`, `current_version_id`, `emergency_contact_name`, `emergency_contact_phone`, `remark`, `created_by`, `created_at`, `updated_at`) VALUES (1, 'G20260912001', 10, '许建国', '13900000010', 'FATHER', 'VALID', 2, '许建国', '13900000010', '青霉素过敏；仅限苏禾教练课程', '前台-马倩', '2026-09-12 10:00:00', '2026-09-20 16:00:00');

INSERT INTO guardian_consent_version (`id`, `consent_id`, `version_no`, `status`, `effective_from`, `effective_until`, `version_remark`, `confirmed_by`, `confirmed_at`, `confirm_relation`, `confirm_note`, `superseded_by_version_id`, `superseded_at`, `content_fingerprint`, `created_by`, `created_at`) VALUES (1, 1, 1, 'SUPERSEDED', '2026-09-12', '2026-12-31', '初版授权', '许建国', '2026-09-12 10:10:00', 'FATHER', '现场签字确认', 2, '2026-09-20 16:00:00', 'seed-v1-20260912', '前台-马倩', '2026-09-12 10:00:00');
INSERT INTO guardian_consent_version (`id`, `consent_id`, `version_no`, `status`, `effective_from`, `effective_until`, `version_remark`, `confirmed_by`, `confirmed_at`, `confirm_relation`, `confirm_note`, `content_fingerprint`, `created_by`, `created_at`) VALUES (2, 1, 2, 'VALID', '2026-09-20', '2026-12-31', '增加传统弓课程与 E-TR-01 器材范围，更新接管人手机号', '许建国', '2026-09-20 16:10:00', 'FATHER', '现场核对证件后确认', 'seed-v2-20260920', '前台-马倩', '2026-09-20 16:00:00');

-- v1 范围：课程 9 / 箭道 1,3 / 器材 9 / 接管人 许建国
INSERT INTO guardian_consent_course (`id`, `version_id`, `course_id`) VALUES (1, 1, 9);
INSERT INTO guardian_consent_lane (`id`, `version_id`, `lane_id`) VALUES (1, 1, 1), (2, 1, 3);
INSERT INTO guardian_consent_equipment (`id`, `version_id`, `equipment_id`) VALUES (1, 1, 9);
INSERT INTO guardian_consent_pickup (`id`, `version_id`, `person_name`, `id_no`, `phone`, `relation`) VALUES (1, 1, '许建国', '1234', '13900000010', 'FATHER');
-- v2 范围：课程 8,9 / 箭道 1,3,4 / 器材 7,9,11 / 接管人 许建国、王秀兰（祖母）
INSERT INTO guardian_consent_course (`id`, `version_id`, `course_id`) VALUES (2, 2, 8), (3, 2, 9);
INSERT INTO guardian_consent_lane (`id`, `version_id`, `lane_id`) VALUES (3, 2, 1), (4, 2, 3), (5, 2, 4);
INSERT INTO guardian_consent_equipment (`id`, `version_id`, `equipment_id`) VALUES (2, 2, 7), (3, 2, 9), (4, 2, 11);
INSERT INTO guardian_consent_pickup (`id`, `version_id`, `person_name`, `id_no`, `phone`, `relation`) VALUES (2, 2, '许建国', '1234', '13900000010', 'FATHER'), (3, 2, '王秀兰', '5678', '13900000011', 'GRANDPARENT');

-- 吴桐（id=6）：授权链 2，v1 处于待监护人确认（草稿已提交，尚不能放行）
INSERT INTO guardian_consent (`id`, `consent_no`, `member_id`, `guardian_name`, `guardian_phone`, `guardian_relation`, `status`, `current_version_id`, `emergency_contact_name`, `emergency_contact_phone`, `remark`, `created_by`, `created_at`, `updated_at`) VALUES (2, 'G20260928001', 6, '吴丽华', '13900000020', 'MOTHER', 'PENDING_CONFIRM', 3, '吴丽华', '13900000020', NULL, '前台-马倩', '2026-09-28 18:00:00', '2026-09-28 18:00:00');
INSERT INTO guardian_consent_version (`id`, `consent_id`, `version_no`, `status`, `effective_from`, `effective_until`, `content_fingerprint`, `created_by`, `created_at`) VALUES (3, 2, 1, 'PENDING_CONFIRM', '2026-09-28', '2027-03-31', 'seed-pending-20260928', '前台-马倩', '2026-09-28 18:00:00');
INSERT INTO guardian_consent_course (`id`, `version_id`, `course_id`) VALUES (4, 3, 2), (5, 3, 9);
INSERT INTO guardian_consent_lane (`id`, `version_id`, `lane_id`) VALUES (6, 3, 3), (7, 3, 4);
INSERT INTO guardian_consent_equipment (`id`, `version_id`, `equipment_id`) VALUES (5, 3, 1), (6, 3, 9);
INSERT INTO guardian_consent_pickup (`id`, `version_id`, `person_name`, `id_no`, `phone`, `relation`) VALUES (4, 3, '吴丽华', '2222', '13900000020', 'MOTHER');

-- 许静一笔已完成的历史交接（v1 基线，已安全交还；历史事实保留可查）
INSERT INTO minor_visit (`id`, `visit_no`, `member_id`, `status`, `consent_id`, `baseline_version_id`, `baseline_fingerprint`, `baseline_frozen`, `pickup_person_name`, `pickup_person_idno`, `pickup_person_phone`, `pickup_person_relation`, `pickup_operator`, `pickup_at`, `start_operator`, `started_at`, `return_person_name`, `return_person_idno`, `return_person_phone`, `return_person_relation`, `qualified_pickup`, `manager_override`, `return_operator`, `returned_at`, `created_by`, `created_at`) VALUES (1, 'V20260920001', 10, 'RETURNED', 1, 1, 'seed-v1-20260912', 0, '许建国', '1234', '13900000010', 'FATHER', '前台-马倩', '2026-09-20 09:30:00', '苏禾', '2026-09-20 09:35:00', '许建国', '1234', '13900000010', 'FATHER', 1, 0, '前台-马倩', '2026-09-20 11:30:00', '前台-马倩', '2026-09-20 09:30:00');
INSERT INTO minor_visit_resource (`id`, `visit_id`, `resource_type`, `resource_id`, `resource_label`, `status`, `release_operator`, `released_at`, `created_at`) VALUES (1, 1, 'COURSE', 9, '青少年安全射箭课', 'RELEASED', '苏禾', '2026-09-20 09:35:00', '2026-09-20 09:30:00'), (2, 1, 'LANE', 3, 'A03', 'RELEASED', '苏禾', '2026-09-20 09:35:00', '2026-09-20 09:30:00'), (3, 1, 'EQUIPMENT', 9, 'E-GR-01', 'RELEASED', '苏禾', '2026-09-20 09:35:00', '2026-09-20 09:30:00');

INSERT INTO guardian_consent_log (`id`, `consent_id`, `version_id`, `version_no`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (1, 1, 1, 1, 'CREATE', NULL, 'DRAFT', '前台-马倩', 'FRONT', '建立监护授权链：许静；监护人 许建国；v1 草稿', '2026-09-12 10:00:00');
INSERT INTO guardian_consent_log (`id`, `consent_id`, `version_id`, `version_no`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (2, 1, 1, 1, 'CONFIRM', 'PENDING_CONFIRM', 'VALID', '许建国', 'GUARDIAN', '监护人现场确认 v1，有效期 2026-09-12 至 2026-12-31', '2026-09-12 10:10:00');
INSERT INTO guardian_consent_log (`id`, `consent_id`, `version_id`, `version_no`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (3, 1, 2, 2, 'NEW_VERSION', 'VALID', 'PENDING_CONFIRM', '前台-马倩', 'FRONT', '范围变更：新增课程「传统弓礼仪与技法」、器材 E-TR-01 / E-AR-01、箭道 A04，接管人新增 王秀兰', '2026-09-20 16:00:00');
INSERT INTO guardian_consent_log (`id`, `consent_id`, `version_id`, `version_no`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (4, 1, 1, 1, 'SUPERSEDE', 'VALID', 'SUPERSEDED', '授权系统', 'SYSTEM', 'v2 确认生效，v1 转已取代：旧版本可查但不得继续放行', '2026-09-20 16:10:00');
INSERT INTO guardian_consent_log (`id`, `consent_id`, `version_id`, `version_no`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (5, 1, 2, 2, 'CONFIRM', 'PENDING_CONFIRM', 'VALID', '许建国', 'GUARDIAN', '监护人现场确认 v2，有效期 2026-09-20 至 2026-12-31', '2026-09-20 16:10:00');
INSERT INTO guardian_consent_log (`id`, `consent_id`, `version_id`, `version_no`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (6, 2, 3, 1, 'CREATE', NULL, 'DRAFT', '前台-马倩', 'FRONT', '建立监护授权链：吴桐；监护人 吴丽华；v1 草稿', '2026-09-28 18:00:00');
INSERT INTO guardian_consent_log (`id`, `consent_id`, `version_id`, `version_no`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (7, 2, 3, 1, 'SUBMIT', 'DRAFT', 'PENDING_CONFIRM', '前台-马倩', 'FRONT', '提交监护人确认', '2026-09-28 18:00:00');

INSERT INTO minor_visit_log (`id`, `visit_id`, `action`, `stage`, `operator`, `role`, `detail`, `created_at`) VALUES (1, 1, 'CREATE', NULL, '前台-马倩', 'FRONT', '建立到馆交接单，等待接管人到场', '2026-09-20 09:30:00');
INSERT INTO minor_visit_log (`id`, `visit_id`, `action`, `stage`, `operator`, `role`, `detail`, `created_at`) VALUES (2, 1, 'PICKUP', 'PENDING_PICKUP', '前台-马倩', 'FRONT', '接管人 许建国（尾号1234）现场核验通过，钉住授权 v1 基线', '2026-09-20 09:30:00');
INSERT INTO minor_visit_log (`id`, `visit_id`, `action`, `stage`, `operator`, `role`, `detail`, `created_at`) VALUES (3, 1, 'RELEASE', 'TRAINING', '苏禾', 'COACH', '训练开始前整组校验通过：课程 青少年安全射箭课、箭道 A03、器材 E-GR-01 均在 v1 范围内', '2026-09-20 09:35:00');
INSERT INTO minor_visit_log (`id`, `visit_id`, `action`, `stage`, `operator`, `role`, `detail`, `created_at`) VALUES (4, 1, 'RETURN', 'PENDING_RETURN', '前台-马倩', 'FRONT', '交还人 许建国 在 v1 接管人名单内，安全交还完成', '2026-09-20 11:30:00');

-- ================= 模块十二：失物保管与认领交接台 =================
-- 失物主表：登记即事实，类别 / 发现位置 / 时间 / 外观特征 / 现场照片 / 发现人一经写入再无 UPDATE 内容的入口；
-- 物品的后续生命（保管、开封、转移、认领、交还、异常、处置）全部沿同一条追加式交接链推进，
-- 绝不靠修改原登记抹掉前序事实。
-- 状态机：REGISTERED 待首次封存 → IN_CUSTODY 保管中 → TO_RETURN 待交还（认领审核通过）
--         → RETURNED 已交还；现场交接异常转 EXCEPTION 异常挂起（暂停交还，原审核结果保留）；
--         超过保管期限无人认领 → TO_DISPOSE 待处置 → DISPOSED 已处置（终态）。
CREATE TABLE IF NOT EXISTS lost_item (
  id                 BIGINT       NOT NULL AUTO_INCREMENT,
  item_no            VARCHAR(24)  NOT NULL COMMENT '失物编号',
  category           VARCHAR(16)  NOT NULL COMMENT '类别：WALLET 钱包证件 / ELECTRONIC 电子设备 / JEWELRY 首饰 / CLOTHING 衣物 / EQUIPMENT 运动器材 / ACCESSORY 配饰挂件 / OTHER 其他',
  public_category    VARCHAR(16)  NOT NULL COMMENT '对外公示用模糊类别：VALUABLE 贵重物品 / ID_BEARING 带证件物品 / NORMAL 普通物品',
  found_location_type VARCHAR(16) NOT NULL COMMENT '发现位置类型：LANE 箭道 / COURSE 课程区 / PUBLIC 公共区域',
  found_lane_id      BIGINT       NULL COMMENT '箭道（found_location_type=LANE 时）',
  found_course_id    BIGINT       NULL COMMENT '课程（found_location_type=COURSE 时）',
  found_area         VARCHAR(64)  NOT NULL COMMENT '发现区域文字（馆内区域，公示仅显示到区域粒度）',
  found_at           DATETIME     NOT NULL COMMENT '发现时间',
  appearance         VARCHAR(500) NOT NULL COMMENT '外观特征（内部核验用，不对认领人公示）',
  photo_refs         VARCHAR(500) NULL COMMENT '现场照片引用清单（逗号分隔；仅工作人员在权限内查看）',
  high_value_flag    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '高价值物品：处置只能走公安移交路径',
  identity_flag      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '带身份信息物品：处置只能走公安移交路径',
  finder_name        VARCHAR(32)  NOT NULL COMMENT '发现人姓名（会员或工作人员）',
  finder_contact     VARCHAR(64)  NULL COMMENT '发现人联系方式（仅工作人员可见）',
  finder_role        VARCHAR(16)  NOT NULL COMMENT 'MEMBER 会员 / STAFF 工作人员',
  registered_by      VARCHAR(32)  NOT NULL COMMENT '登记前台',
  status             VARCHAR(16)  NOT NULL COMMENT 'REGISTERED / IN_CUSTODY / TO_RETURN / EXCEPTION / RETURNED / TO_DISPOSE / DISPOSED',
  -- 当前保管态（每次转移 / 重封存沿链更新为最新事实；历史在封签与轨迹中可完整还原）
  current_custodian  VARCHAR(32)  NULL COMMENT '当前保管人',
  current_custodian_role VARCHAR(16) NULL COMMENT 'FRONT 前台 / KEEPER 保管员',
  current_storage_area VARCHAR(32) NULL COMMENT '当前存放区域：FRONT_DESK 前台失物柜 / SAFE 贵重物品保险柜 / STORAGE 仓库货架',
  current_seal_id    BIGINT       NULL COMMENT '当前封签 id',
  seal_state         VARCHAR(12)  NULL COMMENT 'SEALED 已封存 / BROKEN 已开封',
  -- 当前在办认领（在途认领期间锁定；认领终态后清空，可重新发起新认领）
  active_claim_id    BIGINT       NULL,
  custody_deadline   DATETIME     NOT NULL COMMENT '保管期限（首次封存时确定；逾期进待处置）',
  marked_overdue_by   VARCHAR(32) NULL,
  marked_overdue_at   DATETIME     NULL,
  created_at         DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_lost_item_no (item_no),
  KEY idx_lost_item_status (status),
  KEY idx_lost_item_deadline (custody_deadline)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 失物封签：一枚封签一行，封存时生成，开封即作废（seal_state 由 SEALED 唯一一次转为 BROKEN 并记录开封原因），
-- 重封存另发新签。当前保管人 / 存放区域 / 密封状态随每枚签固化，任何时候都能沿链还原每一任保管人与密封历史。
CREATE TABLE IF NOT EXISTS lost_item_seal (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  item_id       BIGINT       NOT NULL,
  seal_no       VARCHAR(24)  NOT NULL COMMENT '封签号',
  seal_seq      INT          NOT NULL COMMENT '本物品第几枚封签，从 1 连续',
  custodian     VARCHAR(32)  NOT NULL COMMENT '封存时保管人',
  custodian_role VARCHAR(16) NOT NULL COMMENT 'FRONT / KEEPER',
  storage_area  VARCHAR(32)  NOT NULL COMMENT '存放区域',
  seal_state    VARCHAR(12)  NOT NULL COMMENT 'SEALED / BROKEN（只能 SEALED → BROKEN 一次）',
  sealed_by     VARCHAR(32)  NOT NULL,
  sealed_at     DATETIME     NOT NULL,
  broken_by     VARCHAR(32)  NULL,
  broken_at     DATETIME     NULL,
  break_reason  VARCHAR(500) NULL COMMENT '开封原因：INSPECT 工作人员查看 / TRANSFER 转移 / RETURN 现场交还 / RESEAL 异常重新封存前核验',
  reseal_note   VARCHAR(500) NULL COMMENT '重封存说明（从本签开封到新签封存之间的核验情况）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_lost_seal_no (seal_no),
  UNIQUE KEY uk_lost_seal_seq (item_id, seal_seq),
  KEY idx_lost_seal_item (item_id),
  CONSTRAINT fk_lost_seal_item FOREIGN KEY (item_id) REFERENCES lost_item (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 认领申请：会员或未建档访客均可发起；一件物品同一时间至多一笔在途认领（PENDING / NEED_MORE / APPROVED / EXCEPTION），
-- 驳回 / 终止为终态，不能被重新改成已交还；终态后物品如仍在保管可由其他人发起新认领。
CREATE TABLE IF NOT EXISTS lost_claim (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  claim_no         VARCHAR(24)  NOT NULL COMMENT '认领单号',
  item_id          BIGINT       NOT NULL,
  claimant_type    VARCHAR(16)  NOT NULL COMMENT 'MEMBER 现有会员 / VISITOR 未建档访客',
  member_id        BIGINT       NULL COMMENT '会员认领时的会员 id',
  claimant_name    VARCHAR(32)  NOT NULL COMMENT '认领人姓名',
  claimant_phone   VARCHAR(64)  NULL COMMENT '认领人联系方式（敏感信息：仅前台 / 经理在必要环节可见）',
  -- 认领申报（核验依据）：经理只能看到完成核验所需的最少信息
  claim_desc       VARCHAR(500) NOT NULL COMMENT '认领人提供的物品描述',
  lost_time_from   DATETIME     NOT NULL COMMENT '自述遗失时间范围起',
  lost_time_to     DATETIME     NOT NULL COMMENT '自述遗失时间范围止',
  possible_lane_id BIGINT       NULL COMMENT '可能出现的箭道',
  possible_course_id BIGINT     NULL COMMENT '可能出现的课程',
  possible_area    VARCHAR(64)  NULL COMMENT '可能出现的其他馆内区域',
  privacy_features VARCHAR(500) NOT NULL COMMENT '用于核验的隐私特征（不对外公示；教练 / 会员不可见）',
  supplementary    VARCHAR(500) NULL COMMENT '补证材料（按经理要求补充，追加留痕，不覆盖首次申报）',
  status           VARCHAR(16)  NOT NULL COMMENT 'PENDING / NEED_MORE / APPROVED / REJECTED / EXCEPTION / TERMINATED / RETURNED',
  created_by       VARCHAR(32)  NOT NULL COMMENT '录入前台',
  created_at       DATETIME     NOT NULL,
  decided_by       VARCHAR(32)  NULL COMMENT '审核值班经理',
  decided_at       DATETIME     NULL,
  terminate_reason VARCHAR(500) NULL,
  terminated_by    VARCHAR(32)  NULL,
  terminated_at    DATETIME     NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_lost_claim_no (claim_no),
  KEY idx_lost_claim_item (item_id, status),
  CONSTRAINT fk_lost_claim_item FOREIGN KEY (item_id) REFERENCES lost_item (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 认领审核结论（每轮一行，补证重新提交另起一轮，旧结论保留可查）：三项结论分别给出
-- FEATURE 特征匹配 / TIME_PLACE 时间地点吻合 / SUPPORT 补充证明；
-- 终判 APPROVE 通过 / NEED_MORE 要求补证 / REJECT 驳回；驳回与要求补证必须填写原因。
CREATE TABLE IF NOT EXISTS lost_claim_review (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  claim_id        BIGINT       NOT NULL,
  review_round    INT          NOT NULL COMMENT '审核轮次，从 1 递增（补证重提 +1）',
  feature_result  VARCHAR(10)  NOT NULL COMMENT 'MATCH 匹配 / MISMATCH 不符 / UNKNOWN 待核',
  timeplace_result VARCHAR(10) NOT NULL,
  support_result  VARCHAR(10)  NOT NULL,
  feature_note    VARCHAR(500) NULL,
  timeplace_note  VARCHAR(500) NULL,
  support_note    VARCHAR(500) NULL,
  decision        VARCHAR(12)  NOT NULL COMMENT 'APPROVE / NEED_MORE / REJECT',
  review_note     VARCHAR(500) NOT NULL COMMENT '审核意见（要求补证 / 驳回时必填具体原因）',
  reviewed_by     VARCHAR(32)  NOT NULL COMMENT '审核值班经理',
  reviewed_at     DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_lost_review_round (claim_id, review_round),
  KEY idx_lost_review_claim (claim_id),
  CONSTRAINT fk_lost_review_claim FOREIGN KEY (claim_id) REFERENCES lost_claim (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 现场认领交接：审核通过后必须由当前保管人与另一名工作人员双人完成。
-- 异常时本表挂起（exception_*），保留原审核结果与已采集信息，异常处理后用同一条记录继续双人交还，
-- 绝不让一条失败 / 异常的交接被直接改成已交还（状态与双人确认字段只允许写入一次）。
CREATE TABLE IF NOT EXISTS lost_handover (
  id                 BIGINT       NOT NULL AUTO_INCREMENT,
  item_id            BIGINT       NOT NULL,
  claim_id           BIGINT       NOT NULL,
  custodian          VARCHAR(32)  NOT NULL COMMENT '当前保管人（交还第一确认人）',
  custodian_role     VARCHAR(16)  NOT NULL,
  witness            VARCHAR(32)  NULL COMMENT '另一名工作人员（交还第二确认人，必须不同于保管人）',
  witness_role       VARCHAR(16)  NULL COMMENT 'FRONT / KEEPER / COACH / MANAGER',
  receiver_name      VARCHAR(32)  NULL COMMENT '实际领取人（交接现场核验，允许与申报认领人不同，但须经异常流程）',
  receiver_id_type   VARCHAR(16)  NULL COMMENT '证件核验方式：ID_CARD 身份证 / MEMBER_CARD 会员卡 / DRIVING_LICENSE 驾照 / OTHER 其他证件',
  receiver_id_no     VARCHAR(64)  NULL COMMENT '证件核验标识（后四位等）',
  status             VARCHAR(12)  NOT NULL COMMENT 'PENDING 待双人确认 / DONE 已完成 / EXCEPTION 异常挂起',
  confirmed_by       VARCHAR(32)  NULL COMMENT '主持交还的前台',
  handover_at        DATETIME     NULL,
  -- 异常挂起：封签破损 / 实物与登记不符 / 领取人临时变更 / 保管人无法到场
  exception_reason   VARCHAR(20)  NULL COMMENT 'SEAL_DAMAGED / ITEM_MISMATCH / RECIPIENT_CHANGED / CUSTODIAN_ABSENT',
  exception_detail   VARCHAR(500) NULL,
  exception_by       VARCHAR(32)  NULL,
  exception_at       DATETIME     NULL,
  resolution         VARCHAR(20)  NULL COMMENT '经理处置：RESEAL 重新封存 / RECHECK 补充核验 / TERMINATE 终止认领',
  resolution_detail  VARCHAR(500) NULL,
  resolved_by        VARCHAR(32)  NULL,
  resolved_at        DATETIME     NULL,
  created_by         VARCHAR(32)  NOT NULL,
  created_at         DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_lost_handover_claim (item_id, claim_id),
  KEY idx_lost_handover_claim (claim_id),
  CONSTRAINT fk_lost_handover_item FOREIGN KEY (item_id) REFERENCES lost_item (id),
  CONSTRAINT fk_lost_handover_claim FOREIGN KEY (claim_id) REFERENCES lost_claim (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 逾期处置登记：值班经理登记返还公安 / 捐赠 / 报废结果与接收方凭证。
-- 高价值物品 / 带身份信息物品只能 POLICE 返还公安；任何处置路径都必须留接收方凭证。
CREATE TABLE IF NOT EXISTS lost_item_disposal (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  item_id          BIGINT       NOT NULL,
  result           VARCHAR(16)  NOT NULL COMMENT 'POLICE 返还公安 / DONATE 捐赠 / SCRAP 报废',
  receiver_name    VARCHAR(64)  NOT NULL COMMENT '接收方名称（派出所 / 受捐机构 / 报废处理单位）',
  receiver_contact VARCHAR(64)  NULL COMMENT '接收方联系方式',
  receipt_no       VARCHAR(48)  NOT NULL COMMENT '接收方凭证编号（回执单号等）',
  receipt_note     VARCHAR(500) NULL COMMENT '凭证说明',
  disposed_by      VARCHAR(32)  NOT NULL COMMENT '处置值班经理',
  disposed_at      DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_lost_disposal_item (item_id),
  CONSTRAINT fk_lost_disposal_item FOREIGN KEY (item_id) REFERENCES lost_item (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 失物交接链（追加式账本）：登记 / 首次封存 / 查看开封 / 重封存 / 转移 / 认领申请 /
-- 审核（特征 / 时地 / 补证三结论与通过 / 补证 / 驳回）/ 进入待交还 / 异常 / 异常处置 / 双人交还 / 逾期 / 处置，全程留痕。
-- 只追加不修改：任何业务步骤失败都不写本链；重试以物品状态 / 封签状态为闸，不允许重复生成交接记录。
CREATE TABLE IF NOT EXISTS lost_property_log (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  item_id     BIGINT       NOT NULL,
  claim_id    BIGINT       NULL,
  seal_id     BIGINT       NULL,
  action      VARCHAR(24)  NOT NULL COMMENT 'REGISTER / FIRST_SEAL / UNSEAL / RESEAL / TRANSFER / CLAIM_CREATE / REVIEW / NEED_MORE / REJECT / APPROVE / TO_RETURN / EXCEPTION / EXCEPTION_RESOLVE / HANDOVER_DONE / MARK_OVERDUE / DISPOSE',
  before_status VARCHAR(16) NULL,
  after_status  VARCHAR(16) NULL,
  operator    VARCHAR(32)  NOT NULL,
  role        VARCHAR(16)  NOT NULL COMMENT 'FRONT 前台 / KEEPER 保管员 / COACH 教练 / MANAGER 值班经理 / MEMBER 会员 / VISITOR 访客 / SYSTEM 系统',
  detail      VARCHAR(500) NULL,
  created_at  DATETIME     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_lost_log_item (item_id),
  KEY idx_lost_log_claim (claim_id),
  CONSTRAINT fk_lost_log_item FOREIGN KEY (item_id) REFERENCES lost_item (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 失物种子 1：保管中的普通物品（黑色钱包，内含证件 → 身份物品），封签完好
INSERT INTO lost_item (`id`, `item_no`, `category`, `public_category`, `found_location_type`, `found_lane_id`, `found_course_id`, `found_area`, `found_at`, `appearance`, `photo_refs`, `high_value_flag`, `identity_flag`, `finder_name`, `finder_contact`, `finder_role`, `registered_by`, `status`, `current_custodian`, `current_custodian_role`, `current_storage_area`, `current_seal_id`, `seal_state`, `active_claim_id`, `custody_deadline`, `created_at`) VALUES (1, 'L20260920001', 'WALLET', 'ID_BEARING', 'LANE', 7, NULL, 'A07 箭道', '2026-09-20 14:20:00', '黑色对折皮质钱包，内有身份证（陈姓）、银行卡两张、现金若干，边角有磨白痕迹', 'photo://lost/L20260920001-1.jpg', 0, 1, '郑楠', '13800010007', 'MEMBER', '前台-马倩', 'IN_CUSTODY', '前台-马倩', 'FRONT', 'FRONT_DESK', 1, 'SEALED', NULL, '2026-10-20 23:59:59', '2026-09-20 14:35:00');
INSERT INTO lost_item_seal (`id`, `item_id`, `seal_no`, `seal_seq`, `custodian`, `custodian_role`, `storage_area`, `seal_state`, `sealed_by`, `sealed_at`) VALUES (1, 1, 'S20260920001', 1, '前台-马倩', 'FRONT', 'FRONT_DESK', 'SEALED', '前台-马倩', '2026-09-20 14:35:00');
INSERT INTO lost_property_log (`id`, `item_id`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (1, 1, 'REGISTER', NULL, 'REGISTERED', '前台-马倩', 'FRONT', '失物登记：黑色对折皮质钱包；发现位置 A07 箭道；发现人 郑楠（会员）；已拍现场照片', '2026-09-20 14:30:00');
INSERT INTO lost_property_log (`id`, `item_id`, `seal_id`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (2, 1, 1, 'FIRST_SEAL', 'REGISTERED', 'IN_CUSTODY', '前台-马倩', 'FRONT', '值班前台生成封签 S20260920001 并完成首次交接：保管人 前台-马倩，存放 前台失物柜，封签密封；保管期限至 2026-10-20', '2026-09-20 14:35:00');

-- 失物种子 2：保管中，认领申请待经理审核（访客称丢失智能手表）
INSERT INTO lost_item (`id`, `item_no`, `category`, `public_category`, `found_location_type`, `found_lane_id`, `found_course_id`, `found_area`, `found_at`, `appearance`, `photo_refs`, `high_value_flag`, `identity_flag`, `finder_name`, `finder_contact`, `finder_role`, `registered_by`, `status`, `current_custodian`, `current_custodian_role`, `current_storage_area`, `current_seal_id`, `seal_state`, `active_claim_id`, `custody_deadline`, `created_at`) VALUES (2, 'L20260921001', 'ELECTRONIC', 'VALUABLE', 'COURSE', NULL, 3, '二号教学区（瞄点与稳定训练）', '2026-09-21 20:50:00', '黑色运动智能手表，表带有汗渍划痕，表盘背刻缩写 W.T.，充电器不在内', 'photo://lost/L20260921001-1.jpg', 0, 0, '李慕白', NULL, 'STAFF', '前台-马倩', 'IN_CUSTODY', '前台-马倩', 'FRONT', 'FRONT_DESK', 2, 'SEALED', 1, '2026-10-21 23:59:59', '2026-09-21 21:00:00');
INSERT INTO lost_item_seal (`id`, `item_id`, `seal_no`, `seal_seq`, `custodian`, `custodian_role`, `storage_area`, `seal_state`, `sealed_by`, `sealed_at`) VALUES (2, 2, 'S20260921001', 1, '前台-马倩', 'FRONT', 'FRONT_DESK', 'SEALED', '前台-马倩', '2026-09-21 21:00:00');
INSERT INTO lost_property_log (`id`, `item_id`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (3, 2, 'REGISTER', NULL, 'REGISTERED', '前台-马倩', 'FRONT', '失物登记：黑色运动智能手表；发现位置 二号教学区（课程：瞄点与稳定训练）；发现人 李慕白（工作人员）', '2026-09-21 20:55:00');
INSERT INTO lost_property_log (`id`, `item_id`, `seal_id`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (4, 2, 2, 'FIRST_SEAL', 'REGISTERED', 'IN_CUSTODY', '前台-马倩', 'FRONT', '生成封签 S20260921001 完成首次交接：保管人 前台-马倩，存放 前台失物柜；保管期限至 2026-10-21', '2026-09-21 21:00:00');
INSERT INTO lost_claim (`id`, `claim_no`, `item_id`, `claimant_type`, `member_id`, `claimant_name`, `claimant_phone`, `claim_desc`, `lost_time_from`, `lost_time_to`, `possible_lane_id`, `possible_course_id`, `possible_area`, `privacy_features`, `status`, `created_by`, `created_at`) VALUES (1, 'CL20260925001', 2, 'VISITOR', NULL, '王涛', '13700000021', '一块黑色运动手表，表盘背面刻了我名字缩写', '2026-09-21 19:30:00', '2026-09-21 21:00:00', NULL, 3, '二号教学区', '表盘背刻缩写 W.T.；原配硅胶表带第三格有裂口', 'PENDING', '前台-马倩', '2026-09-25 18:00:00');
INSERT INTO lost_property_log (`id`, `item_id`, `claim_id`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (5, 2, 1, 'CLAIM_CREATE', NULL, NULL, '前台-马倩', 'FRONT', '访客 王涛 提交认领申报：自述 9-21 晚在二号教学区上瞄点与稳定训练课时遗失黑色运动手表，已登记隐私特征供值班经理核验', '2026-09-25 18:00:00');

-- 失物种子 3：高价值物品，认领已审核通过、待双人现场交还
INSERT INTO lost_item (`id`, `item_no`, `category`, `public_category`, `found_location_type`, `found_lane_id`, `found_course_id`, `found_area`, `found_at`, `appearance`, `photo_refs`, `high_value_flag`, `identity_flag`, `finder_name`, `finder_contact`, `finder_role`, `registered_by`, `status`, `current_custodian`, `current_custodian_role`, `current_storage_area`, `current_seal_id`, `seal_state`, `active_claim_id`, `custody_deadline`, `created_at`) VALUES (3, 'L20260926001', 'JEWELRY', 'VALUABLE', 'PUBLIC', NULL, NULL, '前台休息区沙发缝隙', '2026-09-26 16:10:00', '金色细项链一条，坠子为小弓箭造型，扣环处有细小划痕，重约 6 克', 'photo://lost/L20260926001-1.jpg', 1, 0, '保洁-孙阿姨', NULL, 'STAFF', '前台-马倩', 'TO_RETURN', '保管员-老周', 'KEEPER', 'SAFE', 4, 'SEALED', 2, '2026-12-25 23:59:59', '2026-09-26 16:20:00');
INSERT INTO lost_item_seal (`id`, `item_id`, `seal_no`, `seal_seq`, `custodian`, `custodian_role`, `storage_area`, `seal_state`, `sealed_by`, `sealed_at`) VALUES (3, 3, 'S20260926001', 1, '前台-马倩', 'FRONT', 'FRONT_DESK', 'SEALED', '前台-马倩', '2026-09-26 16:20:00');
INSERT INTO lost_item_seal (`id`, `item_id`, `seal_no`, `seal_seq`, `custodian`, `custodian_role`, `storage_area`, `seal_state`, `sealed_by`, `sealed_at`) VALUES (4, 3, 'S20260926002', 2, '保管员-老周', 'KEEPER', 'SAFE', 'SEALED', '保管员-老周', '2026-09-26 17:00:00');
UPDATE lost_item_seal SET seal_state = 'BROKEN', broken_by = '保管员-老周', broken_at = '2026-09-26 16:55:00', break_reason = 'TRANSFER', reseal_note = '高价值物品由前台失物柜转入贵重物品保险柜，转移核验外观与登记一致，随后由保管员重封存 S20260926002' WHERE id = 3;
INSERT INTO lost_claim (`id`, `claim_no`, `item_id`, `claimant_type`, `member_id`, `claimant_name`, `claimant_phone`, `claim_desc`, `lost_time_from`, `lost_time_to`, `possible_lane_id`, `possible_course_id`, `possible_area`, `privacy_features`, `status`, `created_by`, `created_at`, `decided_by`, `decided_at`) VALUES (2, 'CL20260927001', 3, 'MEMBER', 8, '何清', '13800010008', '金色细项链，小弓箭吊坠，是去年比赛奖品', '2026-09-26 15:30:00', '2026-09-26 16:30:00', NULL, NULL, '前台休息区', '坠子背面刻有比赛日期 2025.08；扣环内侧有我自己缠的一圈透明鱼线', 'APPROVED', '前台-马倩', '2026-09-27 10:00:00', '值班经理-冯珊', '2026-09-27 11:00:00');
INSERT INTO lost_claim_review (`id`, `claim_id`, `review_round`, `feature_result`, `timeplace_result`, `support_result`, `feature_note`, `timeplace_note`, `support_note`, `decision`, `review_note`, `reviewed_by`, `reviewed_at`) VALUES (1, 2, 1, 'MATCH', 'MATCH', 'MATCH', '隐私特征（坠背刻字、扣环鱼线）与实物一致，描述的弓箭造型吊坠吻合', '自述遗失时段覆盖发现时间 16:10，区域同为前台休息区', '会员参赛记录与所述去年赛事奖品相互印证', 'APPROVE', '三项核验均通过，同意认领，进入双人现场交还', '值班经理-冯珊', '2026-09-27 11:00:00');
INSERT INTO lost_handover (`id`, `item_id`, `claim_id`, `custodian`, `custodian_role`, `status`, `created_by`, `created_at`) VALUES (1, 3, 2, '保管员-老周', 'KEEPER', 'PENDING', '前台-马倩', '2026-09-27 11:05:00');
INSERT INTO lost_property_log (`id`, `item_id`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (6, 3, 'REGISTER', NULL, 'REGISTERED', '前台-马倩', 'FRONT', '失物登记：金色细项链（小弓箭吊坠）；发现位置 前台休息区沙发缝隙；发现人 保洁-孙阿姨；标记为高价值物品', '2026-09-26 16:20:00');
INSERT INTO lost_property_log (`id`, `item_id`, `seal_id`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (7, 3, 3, 'FIRST_SEAL', 'REGISTERED', 'IN_CUSTODY', '前台-马倩', 'FRONT', '生成封签 S20260926001 完成首次交接：保管人 前台-马倩，存放 前台失物柜；高价值物品保管期限 90 天', '2026-09-26 16:20:00');
INSERT INTO lost_property_log (`id`, `item_id`, `seal_id`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (8, 3, 3, 'UNSEAL', NULL, NULL, '保管员-老周', 'KEEPER', '因转移开封：高价值物品按规定移入贵重物品保险柜，封签 S20260926001 作废', '2026-09-26 16:55:00');
INSERT INTO lost_property_log (`id`, `item_id`, `seal_id`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (9, 3, 4, 'TRANSFER', NULL, NULL, '保管员-老周', 'KEEPER', '保管人 前台-马倩 → 保管员-老周；存放区域 前台失物柜 → 贵重物品保险柜；重封存 S20260926002', '2026-09-26 17:00:00');
INSERT INTO lost_property_log (`id`, `item_id`, `claim_id`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (10, 3, 2, 'CLAIM_CREATE', NULL, NULL, '前台-马倩', 'FRONT', '会员 何清 提交认领申报：金色弓箭吊坠项链，自述遗失于前台休息区', '2026-09-27 10:00:00');
INSERT INTO lost_property_log (`id`, `item_id`, `claim_id`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (11, 3, 2, 'REVIEW', NULL, NULL, '值班经理-冯珊', 'MANAGER', '第 1 轮审核：特征匹配 / 时间地点吻合 / 补充证明通过', '2026-09-27 11:00:00');
INSERT INTO lost_property_log (`id`, `item_id`, `claim_id`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (12, 3, 2, 'APPROVE', NULL, NULL, '值班经理-冯珊', 'MANAGER', '审核通过：进入待交还，须由当前保管人与另一名工作人员双人现场确认', '2026-09-27 11:00:00');
INSERT INTO lost_property_log (`id`, `item_id`, `claim_id`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (13, 3, 2, 'TO_RETURN', 'IN_CUSTODY', 'TO_RETURN', '值班经理-冯珊', 'MANAGER', '物品进入待交还，等待保管员-老周与另一名工作人员共同完成现场认领交接', '2026-09-27 11:00:00');

-- 失物种子 4：普通衣物已逾期，待处置（登记 + 首次封签 + 逾期标记）
INSERT INTO lost_item (`id`, `item_no`, `category`, `public_category`, `found_location_type`, `found_lane_id`, `found_course_id`, `found_area`, `found_at`, `appearance`, `photo_refs`, `high_value_flag`, `identity_flag`, `finder_name`, `finder_contact`, `finder_role`, `registered_by`, `status`, `current_custodian`, `current_custodian_role`, `current_storage_area`, `current_seal_id`, `seal_state`, `active_claim_id`, `custody_deadline`, `marked_overdue_by`, `marked_overdue_at`, `created_at`) VALUES (4, 'L20260820001', 'CLOTHING', 'NORMAL', 'PUBLIC', NULL, NULL, '更衣室 3 号柜', '2026-08-20 19:40:00', '灰色连帽运动外套，左袖有墨水点，内领手写“林”字', 'photo://lost/L20260820001-1.jpg', 0, 0, '前台-马倩', NULL, 'STAFF', '前台-马倩', 'TO_DISPOSE', '保管员-老周', 'KEEPER', 'STORAGE', 5, 'SEALED', NULL, '2026-09-19 23:59:59', '值班经理-冯珊', '2026-09-22 09:00:00', '2026-08-20 20:00:00');
INSERT INTO lost_item_seal (`id`, `item_id`, `seal_no`, `seal_seq`, `custodian`, `custodian_role`, `storage_area`, `seal_state`, `sealed_by`, `sealed_at`) VALUES (5, 4, 'S20260820001', 1, '保管员-老周', 'KEEPER', 'STORAGE', 'SEALED', '保管员-老周', '2026-08-21 10:00:00');
INSERT INTO lost_property_log (`id`, `item_id`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (14, 4, 'REGISTER', NULL, 'REGISTERED', '前台-马倩', 'FRONT', '失物登记：灰色连帽运动外套；发现位置 更衣室 3 号柜；发现人 前台-马倩（工作人员代交）', '2026-08-20 20:00:00');
INSERT INTO lost_property_log (`id`, `item_id`, `seal_id`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (15, 4, 5, 'FIRST_SEAL', 'REGISTERED', 'IN_CUSTODY', '保管员-老周', 'KEEPER', '生成封签 S20260820001 完成首次交接：保管人 保管员-老周，存放 仓库货架；普通物品保管期限 30 天', '2026-08-21 10:00:00');
INSERT INTO lost_property_log (`id`, `item_id`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (16, 4, 'MARK_OVERDUE', 'IN_CUSTODY', 'TO_DISPOSE', '值班经理-冯珊', 'MANAGER', '保管期限 2026-09-19 已过且无人认领，转入待处置清单', '2026-09-22 09:00:00');

-- 失物种子 5：身份物品已处置（返还公安，凭证齐全，终态）
INSERT INTO lost_item (`id`, `item_no`, `category`, `public_category`, `found_location_type`, `found_lane_id`, `found_course_id`, `found_area`, `found_at`, `appearance`, `photo_refs`, `high_value_flag`, `identity_flag`, `finder_name`, `finder_contact`, `finder_role`, `registered_by`, `status`, `current_custodian`, `current_custodian_role`, `current_storage_area`, `current_seal_id`, `seal_state`, `active_claim_id`, `custody_deadline`, `marked_overdue_by`, `marked_overdue_at`, `created_at`) VALUES (5, 'L20260710001', 'WALLET', 'ID_BEARING', 'PUBLIC', NULL, NULL, '馆门口停车区', '2026-07-10 20:30:00', '棕色卡包，内含身份证、社保卡、两张会员卡，无现金', 'photo://lost/L20260710001-1.jpg', 0, 1, '会员-吴桐', '13800010006', 'MEMBER', '前台-马倩', 'DISPOSED', NULL, NULL, NULL, 6, 'BROKEN', NULL, '2026-08-09 23:59:59', '值班经理-冯珊', '2026-08-10 09:00:00', '2026-07-10 20:40:00');
INSERT INTO lost_item_seal (`id`, `item_id`, `seal_no`, `seal_seq`, `custodian`, `custodian_role`, `storage_area`, `seal_state`, `sealed_by`, `sealed_at`, `broken_by`, `broken_at`, `break_reason`, `reseal_note`) VALUES (6, 5, 'S20260710001', 1, '前台-马倩', 'FRONT', 'FRONT_DESK', 'BROKEN', '前台-马倩', '2026-07-10 20:45:00', '值班经理-冯珊', '2026-08-11 10:00:00', 'RESEAL', '带身份信息逾期无人认领，按规定移交站前派出所，封签在经理监督下开拆核验');
INSERT INTO lost_item_disposal (`id`, `item_id`, `result`, `receiver_name`, `receiver_contact`, `receipt_no`, `receipt_note`, `disposed_by`, `disposed_at`) VALUES (1, 5, 'POLICE', '站前派出所失物窗口', '0571-88001100', 'POLICE-20260811-018', '内含身份证、社保卡等身份物品整包移交，民警当面清点签收', '值班经理-冯珊', '2026-08-11 10:20:00');
INSERT INTO lost_property_log (`id`, `item_id`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (17, 5, 'REGISTER', NULL, 'REGISTERED', '前台-马倩', 'FRONT', '失物登记：棕色卡包（含身份证 / 社保卡）；发现位置 馆门口停车区；发现人 会员-吴桐；标记为带身份信息物品', '2026-07-10 20:40:00');
INSERT INTO lost_property_log (`id`, `item_id`, `seal_id`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (18, 5, 6, 'FIRST_SEAL', 'REGISTERED', 'IN_CUSTODY', '前台-马倩', 'FRONT', '生成封签 S20260710001 完成首次交接：保管人 前台-马倩，存放 前台失物柜', '2026-07-10 20:45:00');
INSERT INTO lost_property_log (`id`, `item_id`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (19, 5, 'MARK_OVERDUE', 'IN_CUSTODY', 'TO_DISPOSE', '值班经理-冯珊', 'MANAGER', '保管期限 2026-08-09 已过且无人认领，转入待处置；带身份信息物品须走公安移交路径', '2026-08-10 09:00:00');
INSERT INTO lost_property_log (`id`, `item_id`, `seal_id`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (20, 5, 6, 'UNSEAL', NULL, NULL, '值班经理-冯珊', 'MANAGER', '因移交公安开拆核验：封签 S20260710001 作废', '2026-08-11 10:00:00');
INSERT INTO lost_property_log (`id`, `item_id`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (21, 5, 'DISPOSE', 'TO_DISPOSE', 'DISPOSED', '值班经理-冯珊', 'MANAGER', '逾期处置：返还公安；接收方 站前派出所失物窗口；凭证号 POLICE-20260811-018', '2026-08-11 10:20:00');

-- 失物种子 6：待交还交接时发现封签破损，异常挂起（原审核结果保留）
INSERT INTO lost_item (`id`, `item_no`, `category`, `public_category`, `found_location_type`, `found_lane_id`, `found_course_id`, `found_area`, `found_at`, `appearance`, `photo_refs`, `high_value_flag`, `identity_flag`, `finder_name`, `finder_contact`, `finder_role`, `registered_by`, `status`, `current_custodian`, `current_custodian_role`, `current_storage_area`, `current_seal_id`, `seal_state`, `active_claim_id`, `custody_deadline`, `created_at`) VALUES (6, 'L20260918001', 'ELECTRONIC', 'NORMAL', 'LANE', 10, NULL, 'A10 箭道', '2026-09-18 11:30:00', '白色无线降噪耳机一只（右耳），充电盒不在，耳套有明显发黄', 'photo://lost/L20260918001-1.jpg', 0, 0, '会员-许静', '13800010010', 'MEMBER', '前台-马倩', 'EXCEPTION', '前台-马倩', 'FRONT', 'FRONT_DESK', 7, 'BROKEN', 3, '2026-10-18 23:59:59', '2026-09-18 11:45:00');
INSERT INTO lost_item_seal (`id`, `item_id`, `seal_no`, `seal_seq`, `custodian`, `custodian_role`, `storage_area`, `seal_state`, `sealed_by`, `sealed_at`, `broken_by`, `broken_at`, `break_reason`) VALUES (7, 6, 'S20260918001', 1, '前台-马倩', 'FRONT', 'FRONT_DESK', 'BROKEN', '前台-马倩', '2026-09-18 11:45:00', '前台-马倩', '2026-09-29 15:00:00', 'RETURN');
INSERT INTO lost_claim (`id`, `claim_no`, `item_id`, `claimant_type`, `member_id`, `claimant_name`, `claimant_phone`, `claim_desc`, `lost_time_from`, `lost_time_to`, `possible_lane_id`, `possible_course_id`, `possible_area`, `privacy_features`, `status`, `created_by`, `created_at`, `decided_by`, `decided_at`) VALUES (3, 'CL20260928001', 6, 'MEMBER', 2, '林悦', '13800010002', '右耳白色降噪耳机一只，耳套用久了发黄', '2026-09-18 11:00:00', '2026-09-18 12:00:00', 10, NULL, 'A10 附近', '右耳耳机内侧激光序列号后四位 8842', 'EXCEPTION', '前台-马倩', '2026-09-28 16:00:00', '值班经理-冯珊', '2026-09-28 17:00:00');
INSERT INTO lost_claim_review (`id`, `claim_id`, `review_round`, `feature_result`, `timeplace_result`, `support_result`, `feature_note`, `timeplace_note`, `support_note`, `decision`, `review_note`, `reviewed_by`, `reviewed_at`) VALUES (2, 3, 1, 'MATCH', 'MATCH', 'UNKNOWN', '申报的右耳单只、耳套发黄与序列号尾号与实物一致', '遗失时段与发现时间、A10 区域吻合', '未提供购买凭证，但特征与时空均强吻合，予以通过，交还时再次核验证件', 'APPROVE', '特征与时空两项通过，同意认领；交还现场须再次核验证件并双人确认', '值班经理-冯珊', '2026-09-28 17:00:00');
INSERT INTO lost_handover (`id`, `item_id`, `claim_id`, `custodian`, `custodian_role`, `status`, `confirmed_by`, `exception_reason`, `exception_detail`, `exception_by`, `exception_at`, `created_by`, `created_at`) VALUES (2, 6, 3, '前台-马倩', 'FRONT', 'EXCEPTION', NULL, 'SEAL_DAMAGED', '现场准备交还时发现封签 S20260918001 边缘开裂，无法确认开拆历史，暂停交还', '前台-马倩', '2026-09-29 15:05:00', '前台-马倩', '2026-09-29 14:50:00');
INSERT INTO lost_property_log (`id`, `item_id`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (22, 6, 'REGISTER', NULL, 'REGISTERED', '前台-马倩', 'FRONT', '失物登记：白色无线降噪耳机（右耳单只）；发现位置 A10 箭道；发现人 会员-许静', '2026-09-18 11:40:00');
INSERT INTO lost_property_log (`id`, `item_id`, `seal_id`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (23, 6, 7, 'FIRST_SEAL', 'REGISTERED', 'IN_CUSTODY', '前台-马倩', 'FRONT', '生成封签 S20260918001 完成首次交接：保管人 前台-马倩，存放 前台失物柜', '2026-09-18 11:45:00');
INSERT INTO lost_property_log (`id`, `item_id`, `claim_id`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (24, 6, 3, 'CLAIM_CREATE', NULL, NULL, '前台-马倩', 'FRONT', '会员 林悦 提交认领申报：右耳白色降噪耳机，自述遗失于 A10 箭道', '2026-09-28 16:00:00');
INSERT INTO lost_property_log (`id`, `item_id`, `claim_id`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (25, 6, 3, 'REVIEW', NULL, NULL, '值班经理-冯珊', 'MANAGER', '第 1 轮审核：特征匹配 / 时间地点吻合 / 补充证明待核', '2026-09-28 17:00:00');
INSERT INTO lost_property_log (`id`, `item_id`, `claim_id`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (26, 6, 3, 'APPROVE', NULL, NULL, '值班经理-冯珊', 'MANAGER', '审核通过：进入待交还，双人现场交还', '2026-09-28 17:00:00');
INSERT INTO lost_property_log (`id`, `item_id`, `claim_id`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (27, 6, 3, 'TO_RETURN', 'IN_CUSTODY', 'TO_RETURN', '值班经理-冯珊', 'MANAGER', '物品进入待交还', '2026-09-28 17:00:00');
INSERT INTO lost_property_log (`id`, `item_id`, `claim_id`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (28, 6, 7, 'UNSEAL', NULL, NULL, '前台-马倩', 'FRONT', '因现场交还开封：封签 S20260918001 开拆', '2026-09-29 15:00:00');
INSERT INTO lost_property_log (`id`, `item_id`, `claim_id`, `action`, `before_status`, `after_status`, `operator`, `role`, `detail`, `created_at`) VALUES (29, 6, 3, 'EXCEPTION', 'TO_RETURN', 'EXCEPTION', '前台-马倩', 'FRONT', '异常交接：封签破损（边缘开裂，无法确认开拆历史），暂停交还；原审核结果保留，等待值班经理选择重新封存 / 补充核验 / 终止认领', '2026-09-29 15:05:00');
