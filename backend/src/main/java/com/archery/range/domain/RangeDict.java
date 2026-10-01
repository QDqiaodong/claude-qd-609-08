package com.archery.range.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 射箭馆词典与业务规则参数表：箭道状态、会员等级折扣、环数计分值、
 * 课程等级门槛、器材类型与状态等。规则集中放在这里，便于统一调整。
 */
public final class RangeDict {

    private RangeDict() {
    }

    // ---------------- 箭道 ----------------
    public static final List<Integer> DISTANCES = List.of(10, 18, 30, 50);

    private static final Map<String, String> LANE_STATUS = Map.of(
            "OPEN", "开放",
            "OCCUPIED", "占用",
            "MAINTENANCE", "维护",
            "LOCKED", "安全锁定");

    /** 开台单次时长上限（小时） */
    public static final int MAX_OPEN_HOURS = 8;

    // ---------------- 会员 ----------------
    private static final List<String> MEMBER_LEVEL_ORDER = List.of("NORMAL", "SILVER", "GOLD");

    private static final Map<String, String> MEMBER_LEVEL = Map.of(
            "NORMAL", "普通会员",
            "SILVER", "银卡会员",
            "GOLD", "金卡会员");

    /** 会员等级对应折扣率 */
    private static final Map<String, BigDecimal> MEMBER_DISCOUNT = Map.of(
            "NORMAL", BigDecimal.valueOf(1.00),
            "SILVER", BigDecimal.valueOf(0.95),
            "GOLD", BigDecimal.valueOf(0.90));

    /** 累计消费达到此金额可升级到的等级（只升不降） */
    private static final Map<String, BigDecimal> MEMBER_UPGRADE = Map.of(
            "SILVER", BigDecimal.valueOf(3000),
            "GOLD", BigDecimal.valueOf(10000));

    public static final BigDecimal RECHARGE_MIN = BigDecimal.valueOf(100);
    public static final BigDecimal RECHARGE_MAX = BigDecimal.valueOf(10000);

    // ---------------- 环数 ----------------
    /** 允许的环数标记：X（内十）/ 10 ~ 1 / M（脱靶） */
    private static final Map<String, Integer> RING_VALUE = Map.ofEntries(
            Map.entry("X", 10),
            Map.entry("10", 10),
            Map.entry("9", 9),
            Map.entry("8", 8),
            Map.entry("7", 7),
            Map.entry("6", 6),
            Map.entry("5", 5),
            Map.entry("4", 4),
            Map.entry("3", 3),
            Map.entry("2", 2),
            Map.entry("1", 1),
            Map.entry("M", 0));

    /** 计分键盘上出现的环数键（日常计分组用精简键盘） */
    public static final List<String> KEYPAD_RINGS = List.of("X", "10", "9", "8", "7", "6", "M");

    /** 团体淘汰赛计分局用完整环数口径：X、10 至 1、M */
    public static final List<String> ALL_RINGS = List.of("X", "10", "9", "8", "7", "6", "5", "4", "3", "2", "1", "M");

    /** 一组箭支数只能是 6 支或 12 支 */
    public static final List<Integer> GROUP_SIZES = List.of(6, 12);

    // ---------------- 课程 ----------------
    private static final Map<String, String> COURSE_LEVEL = Map.of(
            "BASIC", "初级",
            "ADVANCED", "进阶",
            "COMPETITION", "竞技");

    /** 课程等级对会员等级的最低要求 */
    private static final Map<String, String> COURSE_MIN_MEMBER_LEVEL = Map.of(
            "BASIC", "NORMAL",
            "ADVANCED", "SILVER",
            "COMPETITION", "GOLD");

    // ---------------- 器材 ----------------
    private static final Map<String, String> EQUIP_TYPE = Map.of(
            "RECURVE", "反曲弓",
            "COMPOUND", "复合弓",
            "TRADITIONAL", "传统弓",
            "GEAR", "护具",
            "ARROW", "箭支");

    // ---------------- 器材损伤清算 ----------------
    /** 损伤严重程度：轻微 / 中等 / 严重（对应计价规则的三个档） */
    private static final Map<String, String> DAMAGE_SEVERITY = Map.of(
            "LIGHT", "轻微",
            "MEDIUM", "中等",
            "SEVERE", "严重");

    /** 清算单状态：检查中 → 待结清 → 已结清；基线漂移转待重新核对（核对后回到待结清） */
    private static final Map<String, String> SETTLE_STATUS = Map.of(
            "INSPECTING", "检查中",
            "PENDING_SETTLE", "待结清",
            "RECHECK", "待重新核对",
            "SETTLED", "已结清");

    /** 单个损伤项的异议处理结论 */
    private static final Map<String, String> DISPUTE_STATUS = Map.of(
            "DISPUTING", "异议处理中",
            "MAINTAIN", "维持原费用",
            "REDUCE", "经理减免",
            "REVOKE", "撤销该项费用");

    /** 待缴费用状态：待缴 → 已缴清 / 已撤销 */
    private static final Map<String, String> DEBT_STATUS = Map.of(
            "UNPAID", "待缴",
            "PAID", "已缴清",
            "REVOKED", "已撤销");

    /** 清算单操作轨迹动作 */
    private static final Map<String, String> SETTLE_ACTION = Map.ofEntries(
            Map.entry("START", "发起损伤检查"),
            Map.entry("ADD_DAMAGE", "登记损伤项"),
            Map.entry("REMOVE_DAMAGE", "移除损伤项"),
            Map.entry("FINISH_INSPECT", "结束检查（无损）"),
            Map.entry("WAIVE", "经理减免损伤项"),
            Map.entry("DISPUTE", "会员提出异议"),
            Map.entry("DISPUTE_MAINTAIN", "异议处理：维持"),
            Map.entry("DISPUTE_REDUCE", "异议处理：减免"),
            Map.entry("DISPUTE_REVOKE", "异议处理：撤销"),
            Map.entry("RECHECK", "基线变化，转待重新核对"),
            Map.entry("REBIND", "重新核对完成"),
            Map.entry("SETTLE", "结清清算单"),
            Map.entry("REPAY", "补缴欠款解限"),
            Map.entry("ABANDON", "放弃清算（回到租出）"));

    /** 补缴 / 欠款轨迹的角色 */
    private static final Map<String, String> SETTLE_ROLE = Map.of(
            "KEEPER", "器材管理员",
            "MANAGER", "值班经理",
            "MEMBER", "会员",
            "SYSTEM", "清算系统");

    /** 结清后器材去向 */
    private static final Map<String, String> DAMAGE_DISPOSAL = Map.of(
            "NONE", "回到在库",
            "REPAIR", "送修",
            "SCRAP", "报废封存");

    // ---------------- 弓种能力认证 ----------------
    /** 可认证的弓种（器材类型的弓类子集） */
    private static final Map<String, String> BOW_TYPE = Map.of(
            "RECURVE", "反曲弓",
            "COMPOUND", "复合弓",
            "TRADITIONAL", "传统弓");

    /** 开台时需要有效弓种认证的射距（米）：30 / 50 米为认证射距，10 / 18 米对所有会员开放 */
    public static final List<Integer> CERT_GATED_DISTANCES = List.of(30, 50);

    /** 认证申请状态机：待复核 → 已通过 / 已驳回 / 待补充证据；已通过的认证可撤回或被取代；已过期为展示态 */
    private static final Map<String, String> CERT_STATUS = Map.of(
            "PENDING", "待复核",
            "APPROVED", "已通过",
            "REJECTED", "已驳回",
            "NEED_MORE", "待补充证据",
            "WITHDRAWN", "已撤回",
            "SUPERSEDED", "已被取代",
            "EXPIRED", "已过期");

    /** 认证规则状态 */
    private static final Map<String, String> CERT_RULE_STATUS = Map.of(
            "ACTIVE", "现行",
            "RETIRED", "已停用");

    /** 认证复核动作 */
    private static final Map<String, String> CERT_DECISION = Map.of(
            "APPROVE", "通过",
            "REJECT", "驳回",
            "NEED_MORE", "要求补充证据");

    /** 认证台角色 */
    private static final Map<String, String> CERT_ROLE = Map.of(
            "COACH", "教练",
            "SYSTEM", "认证系统");

    /** 认证状态轨迹动作 */
    private static final Map<String, String> CERT_ACTION = Map.ofEntries(
            Map.entry("CREATE", "建立认证申请"),
            Map.entry("EVAL", "系统预评"),
            Map.entry("APPROVE", "复核通过"),
            Map.entry("REJECT", "复核驳回"),
            Map.entry("NEED_MORE", "要求补充证据"),
            Map.entry("RESUBMIT", "补充证据重新评定"),
            Map.entry("WITHDRAW", "撤回认证"),
            Map.entry("SUPERSEDE", "被新认证取代"));

    private static final Map<String, String> EQUIP_STATUS = Map.of(
            "INSTOCK", "在库",
            "RENTED", "租出",
            "REPAIR", "维修",
            "LOCKED", "安全锁定",
            "SETTLING", "归还处理中",
            "SCRAPPED", "报废封存");

    // ---------------- 回合状态 ----------------
    private static final Map<String, String> ROUND_STATUS = Map.of(
            "ONGOING", "进行中",
            "PAUSED", "暂停中",
            "SUBMITTED", "已提交");

    // ---------------- 安全停射联锁 ----------------
    /** 停射事件状态机：待处置 → 分项复核 → 待放行 → 已放行（复核不通过退回待处置） */
    private static final Map<String, String> SAFETY_STATUS = Map.of(
            "PENDING", "待处置",
            "REVIEWING", "分项复核",
            "CLEARED", "待放行",
            "RELEASED", "已放行");

    /** 停射原因类别 */
    private static final Map<String, String> SAFETY_REASON = Map.of(
            "LANE_DEVICE", "箭道设备异常",
            "PERSON_INTRUSION", "人员闯入射线",
            "EQUIP_SUSPECT", "疑似器材故障",
            "OTHER", "其他");

    /** 严重级别 */
    private static final Map<String, String> SAFETY_SEVERITY = Map.of(
            "NOTICE", "一般",
            "MAJOR", "严重",
            "CRITICAL", "紧急");

    /** 分项复核项：教练确认射线与人员安全；器材管理员确认器材已检查 */
    private static final Map<String, String> REVIEW_ITEM = Map.of(
            "RANGE", "射线与人员安全",
            "EQUIPMENT", "器材检查");

    /** 复核结论 */
    private static final Map<String, String> CONCLUSION = Map.of(
            "PASS", "通过",
            "FAIL", "不通过");

    /** 联锁台角色 */
    private static final Map<String, String> SAFETY_ROLE = Map.of(
            "MANAGER", "值班经理",
            "COACH", "教练",
            "KEEPER", "器材管理员",
            "SYSTEM", "联锁系统");

    /** 状态轨迹动作 */
    private static final Map<String, String> SAFETY_ACTION = Map.of(
            "CREATE", "建立停射事件",
            "START_REVIEW", "发起分项复核",
            "REVIEW_PASS", "复核通过",
            "REVIEW_FAIL", "复核不通过",
            "BACK_PENDING", "退回待处置",
            "CLEARED", "两项复核均通过",
            "RELEASE", "复射放行");

    // ---------------- 团体淘汰赛 ----------------
    /** 参赛队数只能是 4 或 8（对应两轮或三轮单淘汰） */
    public static final List<Integer> TEAM_SIZES = List.of(4, 8);

    /** 每队固定三名会员 */
    public static final int MEMBERS_PER_TEAM = 3;

    /** 每场局数允许范围（至少 1 局） */
    public static final List<Integer> ENDS_PER_MATCH = List.of(1, 2, 3, 4, 5, 6);

    /** 每名队员每局规定箭数允许范围 */
    public static final List<Integer> ARROWS_PER_END = List.of(1, 2, 3);

    /** 赛事状态 */
    private static final Map<String, String> TOURNAMENT_STATUS = Map.of(
            "DRAFT", "报名中",
            "ONGOING", "进行中",
            "FINISHED", "已完赛");

    /** 场次状态 */
    private static final Map<String, String> MATCH_STATUS = Map.of(
            "PENDING", "待开赛",
            "ONGOING", "进行中",
            "AWAIT_CONFIRM", "待确认胜者",
            "CONFIRMED", "已确认");

    /** 场次阶段 */
    private static final Map<String, String> MATCH_STAGE = Map.of(
            "REGULATION", "规定局",
            "SHOOTOFF", "加赛箭");

    /** 淘汰赛角色 */
    private static final Map<String, String> TOURNAMENT_ROLE = Map.of(
            "MANAGER", "值班经理",
            "REFEREE", "裁判",
            "MEMBER", "普通会员",
            "SYSTEM", "计分系统");

    /** 赛事操作轨迹动作 */
    private static final Map<String, String> TOURNAMENT_ACTION = Map.ofEntries(
            Map.entry("CREATE", "建立赛事"),
            Map.entry("ADD_TEAM", "新增参赛队"),
            Map.entry("UPDATE_TEAM", "调整参赛队"),
            Map.entry("REMOVE_TEAM", "移除参赛队"),
            Map.entry("START", "开赛并生成对阵"),
            Map.entry("CONFIRM_END", "确认局成绩"),
            Map.entry("RETRACT_END", "撤回最后一局"),
            Map.entry("ENTER_SHOOTOFF", "进入加赛箭"),
            Map.entry("LOCK_SHOOTOFF", "锁定加赛轮"),
            Map.entry("CONTINUE_SHOOTOFF", "加赛仍平，继续下一轮"),
            Map.entry("CONFIRM_WINNER", "确认胜者"),
            Map.entry("ADVANCE_AUTO", "自动带入晋级队"));

    /** 各队数对应的轮次名称 */
    public static String roundName(int teamSize, int roundNo) {
        int totalRounds = teamSize == 4 ? 2 : 3;
        if (roundNo < totalRounds) {
            return roundNo == 1 ? "首轮" : (roundNo == 2 ? "半决赛" : "第" + roundNo + "轮");
        }
        return "决赛";
    }

    // ---------------- 取值方法 ----------------

    public static boolean isValidDistance(Integer distance) {
        return distance != null && DISTANCES.contains(distance);
    }

    public static boolean isValidLaneStatus(String status) {
        return status != null && LANE_STATUS.containsKey(status);
    }

    public static String laneStatusName(String status) {
        return status == null ? "" : LANE_STATUS.getOrDefault(status, status);
    }

    public static boolean isValidMemberLevel(String level) {
        return level != null && MEMBER_LEVEL.containsKey(level);
    }

    public static String memberLevelName(String level) {
        return level == null ? "" : MEMBER_LEVEL.getOrDefault(level, level);
    }

    public static BigDecimal memberDiscount(String level) {
        return MEMBER_DISCOUNT.getOrDefault(level, BigDecimal.valueOf(1.00));
    }

    public static int memberLevelRank(String level) {
        int index = MEMBER_LEVEL_ORDER.indexOf(level);
        return index < 0 ? 0 : index + 1;
    }

    /** 依据累计消费算出应得的最高等级 */
    public static String levelByTotalSpend(BigDecimal totalSpend) {
        BigDecimal total = totalSpend == null ? BigDecimal.ZERO : totalSpend;
        if (total.compareTo(MEMBER_UPGRADE.get("GOLD")) >= 0) {
            return "GOLD";
        }
        if (total.compareTo(MEMBER_UPGRADE.get("SILVER")) >= 0) {
            return "SILVER";
        }
        return "NORMAL";
    }

    public static boolean isValidRing(String ring) {
        return ring != null && RING_VALUE.containsKey(ring);
    }

    public static int ringValue(String ring) {
        return RING_VALUE.getOrDefault(ring, 0);
    }

    public static boolean isValidGroupSize(Integer arrowCount) {
        return arrowCount != null && GROUP_SIZES.contains(arrowCount);
    }

    public static boolean isValidCourseLevel(String level) {
        return level != null && COURSE_LEVEL.containsKey(level);
    }

    public static String courseLevelName(String level) {
        return level == null ? "" : COURSE_LEVEL.getOrDefault(level, level);
    }

    public static String courseMinMemberLevel(String level) {
        return COURSE_MIN_MEMBER_LEVEL.getOrDefault(level, "NORMAL");
    }

    public static boolean isValidEquipType(String type) {
        return type != null && EQUIP_TYPE.containsKey(type);
    }

    public static String equipTypeName(String type) {
        return type == null ? "" : EQUIP_TYPE.getOrDefault(type, type);
    }

    // ---------------- 器材损伤清算取值 ----------------

    public static boolean isValidDamageSeverity(String severity) {
        return severity != null && DAMAGE_SEVERITY.containsKey(severity);
    }

    public static String damageSeverityName(String severity) {
        return severity == null ? "" : DAMAGE_SEVERITY.getOrDefault(severity, severity);
    }

    public static String settleStatusName(String status) {
        return status == null ? "" : SETTLE_STATUS.getOrDefault(status, status);
    }

    public static String disputeStatusName(String status) {
        return status == null ? "" : DISPUTE_STATUS.getOrDefault(status, status);
    }

    public static String debtStatusName(String status) {
        return status == null ? "" : DEBT_STATUS.getOrDefault(status, status);
    }

    public static String settleActionName(String action) {
        return action == null ? "" : SETTLE_ACTION.getOrDefault(action, action);
    }

    public static String settleRoleName(String role) {
        return role == null ? "" : SETTLE_ROLE.getOrDefault(role, role);
    }

    public static String damageDisposalName(String disposal) {
        return disposal == null ? "" : DAMAGE_DISPOSAL.getOrDefault(disposal, disposal);
    }

    // ---------------- 弓种能力认证取值 ----------------

    public static boolean isValidBowType(String type) {
        return type != null && BOW_TYPE.containsKey(type);
    }

    public static String bowTypeName(String type) {
        return type == null ? "" : BOW_TYPE.getOrDefault(type, type);
    }

    public static boolean isValidCertStatus(String status) {
        return status != null && CERT_STATUS.containsKey(status);
    }

    public static String certStatusName(String status) {
        return status == null ? "" : CERT_STATUS.getOrDefault(status, status);
    }

    public static boolean isValidCertRuleStatus(String status) {
        return status != null && CERT_RULE_STATUS.containsKey(status);
    }

    public static String certRuleStatusName(String status) {
        return status == null ? "" : CERT_RULE_STATUS.getOrDefault(status, status);
    }

    public static boolean isValidCertDecision(String decision) {
        return decision != null && CERT_DECISION.containsKey(decision);
    }

    public static String certDecisionName(String decision) {
        return decision == null ? "" : CERT_DECISION.getOrDefault(decision, decision);
    }

    public static String certRoleName(String role) {
        return role == null ? "" : CERT_ROLE.getOrDefault(role, role);
    }

    public static String certActionName(String action) {
        return action == null ? "" : CERT_ACTION.getOrDefault(action, action);
    }

    public static boolean isValidEquipStatus(String status) {
        return status != null && EQUIP_STATUS.containsKey(status);
    }

    public static String equipStatusName(String status) {
        return status == null ? "" : EQUIP_STATUS.getOrDefault(status, status);
    }

    public static boolean isValidRoundStatus(String status) {
        return status != null && ROUND_STATUS.containsKey(status);
    }

    public static String roundStatusName(String status) {
        return status == null ? "" : ROUND_STATUS.getOrDefault(status, status);
    }

    // ---------------- 安全停射联锁取值 ----------------

    public static boolean isValidSafetyReason(String reason) {
        return reason != null && SAFETY_REASON.containsKey(reason);
    }

    public static String safetyReasonName(String reason) {
        return reason == null ? "" : SAFETY_REASON.getOrDefault(reason, reason);
    }

    public static boolean isValidSafetySeverity(String severity) {
        return severity != null && SAFETY_SEVERITY.containsKey(severity);
    }

    public static String safetySeverityName(String severity) {
        return severity == null ? "" : SAFETY_SEVERITY.getOrDefault(severity, severity);
    }

    public static String safetyStatusName(String status) {
        return status == null ? "" : SAFETY_STATUS.getOrDefault(status, status);
    }

    public static boolean isValidReviewItem(String item) {
        return item != null && REVIEW_ITEM.containsKey(item);
    }

    public static String reviewItemName(String item) {
        return item == null ? "" : REVIEW_ITEM.getOrDefault(item, item);
    }

    public static boolean isValidConclusion(String conclusion) {
        return conclusion != null && CONCLUSION.containsKey(conclusion);
    }

    public static String conclusionName(String conclusion) {
        return conclusion == null ? "" : CONCLUSION.getOrDefault(conclusion, conclusion);
    }

    public static boolean isValidSafetyRole(String role) {
        return role != null && SAFETY_ROLE.containsKey(role);
    }

    public static String safetyRoleName(String role) {
        return role == null ? "" : SAFETY_ROLE.getOrDefault(role, role);
    }

    public static String safetyActionName(String action) {
        return action == null ? "" : SAFETY_ACTION.getOrDefault(action, action);
    }

    // ---------------- 团体淘汰赛取值 ----------------

    public static boolean isValidTeamSize(Integer teamSize) {
        return teamSize != null && TEAM_SIZES.contains(teamSize);
    }

    public static boolean isValidEndsPerMatch(Integer ends) {
        return ends != null && ENDS_PER_MATCH.contains(ends);
    }

    public static boolean isValidArrowsPerEnd(Integer arrows) {
        return arrows != null && ARROWS_PER_END.contains(arrows);
    }

    public static String tournamentStatusName(String status) {
        return status == null ? "" : TOURNAMENT_STATUS.getOrDefault(status, status);
    }

    public static String matchStatusName(String status) {
        return status == null ? "" : MATCH_STATUS.getOrDefault(status, status);
    }

    public static String matchStageName(String stage) {
        return stage == null ? "" : MATCH_STAGE.getOrDefault(stage, stage);
    }

    public static String tournamentRoleName(String role) {
        return role == null ? "" : TOURNAMENT_ROLE.getOrDefault(role, role);
    }

    public static String tournamentActionName(String action) {
        return action == null ? "" : TOURNAMENT_ACTION.getOrDefault(action, action);
    }

    // ---------------- 未成年会员监护授权台 ----------------
    /** 授权链状态：草稿 → 待监护人确认 → 有效 ⇄ 暂停 → 撤销 */
    private static final Map<String, String> GUARDIAN_CONSENT_STATUS = Map.of(
            "DRAFT", "草稿",
            "PENDING_CONFIRM", "待监护人确认",
            "VALID", "有效",
            "SUSPENDED", "暂停",
            "REVOKED", "撤销");

    /** 授权版本状态（比链多：已被取代 / 已拒绝，旧版本仍可查询） */
    private static final Map<String, String> CONSENT_VERSION_STATUS = Map.of(
            "DRAFT", "草稿",
            "PENDING_CONFIRM", "待监护人确认",
            "VALID", "有效",
            "SUPERSEDED", "已被取代",
            "SUSPENDED", "暂停",
            "REVOKED", "已撤销",
            "REJECTED", "已拒绝");

    /** 到馆交接阶段 */
    private static final Map<String, String> VISIT_STATUS = Map.of(
            "PENDING_PICKUP", "待接管",
            "TRAINING", "训练中",
            "PENDING_RETURN", "待交还",
            "RETURNED", "已交还",
            "CANCELED", "已取消");

    /** 交接资源类型 */
    private static final Map<String, String> VISIT_RESOURCE_TYPE = Map.of(
            "COURSE", "课程",
            "LANE", "箭道",
            "EQUIPMENT", "器材");

    /** 交接资源台账状态 */
    private static final Map<String, String> VISIT_RESOURCE_STATUS = Map.of(
            "PENDING", "待放行",
            "RELEASED", "已放行",
            "FROZEN", "已冻结",
            "REJECTED", "已拦截");

    /** 监护人 / 接管人与未成年人关系 */
    private static final Map<String, String> GUARDIAN_RELATION = Map.of(
            "FATHER", "父亲",
            "MOTHER", "母亲",
            "GRANDPARENT", "祖父母 / 外祖父母",
            "OTHER", "其他法定监护人");

    /** 监护授权台角色 */
    private static final Map<String, String> GUARDIAN_ROLE = Map.of(
            "FRONT", "前台",
            "COACH", "教练",
            "KEEPER", "器材管理员",
            "MANAGER", "值班经理",
            "GUARDIAN", "监护人",
            "SYSTEM", "授权系统");

    /** 授权链轨迹动作 */
    private static final Map<String, String> GUARDIAN_ACTION = Map.of(
            "CREATE", "建立授权链",
            "SUBMIT", "提交监护人确认",
            "CONFIRM", "监护人确认生效",
            "REJECT", "监护人拒绝确认",
            "RECALL", "撤回到草稿",
            "NEW_VERSION", "发布新版本",
            "SUPERSEDE", "旧版本被取代",
            "SUSPEND", "暂停授权",
            "RESUME", "恢复授权",
            "REVOKE", "撤销授权");

    /** 现场交接轨迹动作 */
    private static final Map<String, String> VISIT_ACTION = Map.of(
            "CREATE", "建立到馆交接",
            "PICKUP", "现场接管",
            "RELEASE", "训练资源放行",
            "BLOCK", "放行拦截",
            "FREEZE", "授权基线漂移冻结",
            "RECHECK", "按新基线重新核对",
            "WAIT_RETURN", "训练结束待交还",
            "EXCEPTION", "异常处置",
            "RETURN", "安全交还",
            "CANCEL", "取消交接");

    public static boolean isValidGuardianRelation(String relation) {
        return relation != null && GUARDIAN_RELATION.containsKey(relation);
    }

    public static String guardianRelationName(String relation) {
        return relation == null ? "" : GUARDIAN_RELATION.getOrDefault(relation, relation);
    }

    public static String consentStatusName(String status) {
        return status == null ? "" : GUARDIAN_CONSENT_STATUS.getOrDefault(status, status);
    }

    public static String consentVersionStatusName(String status) {
        return status == null ? "" : CONSENT_VERSION_STATUS.getOrDefault(status, status);
    }

    public static String visitStatusName(String status) {
        return status == null ? "" : VISIT_STATUS.getOrDefault(status, status);
    }

    public static String visitResourceTypeName(String type) {
        return type == null ? "" : VISIT_RESOURCE_TYPE.getOrDefault(type, type);
    }

    public static String visitResourceStatusName(String status) {
        return status == null ? "" : VISIT_RESOURCE_STATUS.getOrDefault(status, status);
    }

    public static String guardianRoleName(String role) {
        return role == null ? "" : GUARDIAN_ROLE.getOrDefault(role, role);
    }

    public static String guardianActionName(String action) {
        return action == null ? "" : GUARDIAN_ACTION.getOrDefault(action, action);
    }

    public static String visitActionName(String action) {
        return action == null ? "" : VISIT_ACTION.getOrDefault(action, action);
    }

    // ---------------- 模块十二：失物保管与认领交接台 ----------------
    /** 物品精确类别（工作人员内部使用，不直接对认领人公示） */
    private static final Map<String, String> LOST_CATEGORY = Map.of(
            "WALLET", "钱包证件",
            "ELECTRONIC", "电子设备",
            "JEWELRY", "首饰",
            "CLOTHING", "衣物",
            "EQUIPMENT", "运动器材",
            "ACCESSORY", "配饰挂件",
            "OTHER", "其他物品");

    /** 对外公示用模糊类别（只让真正的失主能凭细节认回） */
    private static final Map<String, String> LOST_PUBLIC_CATEGORY = Map.of(
            "VALUABLE", "贵重物品",
            "ID_BEARING", "带证件物品",
            "NORMAL", "普通物品");

    /** 物品状态机 */
    private static final Map<String, String> LOST_STATUS = Map.of(
            "REGISTERED", "待首次封存",
            "IN_CUSTODY", "保管中",
            "TO_RETURN", "待交还",
            "EXCEPTION", "异常挂起",
            "RETURNED", "已交还",
            "TO_DISPOSE", "待处置",
            "DISPOSED", "已处置");

    /** 发现位置类型 */
    private static final Map<String, String> FOUND_LOCATION_TYPE = Map.of(
            "LANE", "箭道",
            "COURSE", "课程区",
            "PUBLIC", "公共区域");

    /** 存放区域 */
    private static final Map<String, String> STORAGE_AREA = Map.of(
            "FRONT_DESK", "前台失物柜",
            "SAFE", "贵重物品保险柜",
            "STORAGE", "仓库货架");

    /** 封签密封状态 */
    private static final Map<String, String> SEAL_STATE = Map.of(
            "SEALED", "已封存",
            "BROKEN", "已开封");

    /** 开封原因 */
    private static final Map<String, String> SEAL_BREAK_REASON = Map.of(
            "INSPECT", "工作人员查看",
            "TRANSFER", "保管转移",
            "RETURN", "现场交还",
            "RESEAL", "异常重新封存前核验");

    /** 认领人类型 */
    private static final Map<String, String> CLAIMANT_TYPE = Map.of(
            "MEMBER", "现有会员",
            "VISITOR", "未建档访客");

    /** 认领申请状态 */
    private static final Map<String, String> CLAIM_STATUS = Map.of(
            "PENDING", "待审核",
            "NEED_MORE", "待补证",
            "APPROVED", "审核通过",
            "REJECTED", "已驳回",
            "EXCEPTION", "异常挂起",
            "TERMINATED", "已终止",
            "RETURNED", "已交还");

    /** 审核单项结论 */
    private static final Map<String, String> REVIEW_RESULT = Map.of(
            "MATCH", "匹配",
            "MISMATCH", "不符",
            "UNKNOWN", "待核");

    /** 审核终判 */
    private static final Map<String, String> REVIEW_DECISION = Map.of(
            "APPROVE", "审核通过",
            "NEED_MORE", "要求补证",
            "REJECT", "驳回");

    /** 现场交接状态 */
    private static final Map<String, String> HANDOVER_STATUS = Map.of(
            "PENDING", "待双人确认",
            "DONE", "已完成",
            "EXCEPTION", "异常挂起");

    /** 异常交接原因 */
    private static final Map<String, String> HANDOVER_EXCEPTION = Map.of(
            "SEAL_DAMAGED", "封签破损",
            "ITEM_MISMATCH", "实物与登记不符",
            "RECIPIENT_CHANGED", "领取人临时变更",
            "CUSTODIAN_ABSENT", "保管人无法到场");

    /** 异常处置方式 */
    private static final Map<String, String> EXCEPTION_RESOLUTION = Map.of(
            "RESEAL", "重新封存",
            "RECHECK", "补充核验",
            "TERMINATE", "终止本次认领");

    /** 证件核验方式 */
    private static final Map<String, String> ID_VERIFY_TYPE = Map.of(
            "ID_CARD", "身份证",
            "MEMBER_CARD", "会员卡",
            "DRIVING_LICENSE", "驾照",
            "OTHER", "其他证件");

    /** 逾期处置结果 */
    private static final Map<String, String> DISPOSAL_RESULT = Map.of(
            "POLICE", "返还公安",
            "DONATE", "捐赠",
            "SCRAP", "报废");

    /** 失物台角色 */
    private static final Map<String, String> LOST_ROLE = Map.of(
            "FRONT", "前台",
            "KEEPER", "保管员",
            "COACH", "教练",
            "MANAGER", "值班经理",
            "MEMBER", "会员",
            "VISITOR", "访客",
            "SYSTEM", "失物系统");

    /** 交接链动作 */
    private static final Map<String, String> LOST_ACTION = Map.ofEntries(
            Map.entry("REGISTER", "失物登记"),
            Map.entry("FIRST_SEAL", "首次封存交接"),
            Map.entry("UNSEAL", "开封"),
            Map.entry("RESEAL", "重新封存"),
            Map.entry("TRANSFER", "保管转移"),
            Map.entry("CLAIM_CREATE", "提交认领申报"),
            Map.entry("REVIEW", "分项审核"),
            Map.entry("NEED_MORE", "要求补证"),
            Map.entry("REJECT", "驳回认领"),
            Map.entry("APPROVE", "审核通过"),
            Map.entry("TO_RETURN", "进入待交还"),
            Map.entry("EXCEPTION", "异常交接"),
            Map.entry("EXCEPTION_RESOLVE", "异常处置"),
            Map.entry("HANDOVER_DONE", "双人现场交还"),
            Map.entry("MARK_OVERDUE", "逾期转待处置"),
            Map.entry("DISPOSE", "逾期处置"));

    /** 普通物品保管期限（天） */
    public static final int LOST_KEEP_DAYS_NORMAL = 30;
    /** 高价值物品保管期限（天） */
    public static final int LOST_KEEP_DAYS_HIGH_VALUE = 90;

    public static boolean isValidLostCategory(String c) {
        return c != null && LOST_CATEGORY.containsKey(c);
    }

    public static String lostCategoryName(String c) {
        return c == null ? "" : LOST_CATEGORY.getOrDefault(c, c);
    }

    public static String lostPublicCategoryName(String c) {
        return c == null ? "" : LOST_PUBLIC_CATEGORY.getOrDefault(c, c);
    }

    public static String lostStatusName(String s) {
        return s == null ? "" : LOST_STATUS.getOrDefault(s, s);
    }

    public static String foundLocationTypeName(String t) {
        return t == null ? "" : FOUND_LOCATION_TYPE.getOrDefault(t, t);
    }

    public static String storageAreaName(String a) {
        return a == null ? "" : STORAGE_AREA.getOrDefault(a, a);
    }

    public static String sealStateName(String s) {
        return s == null ? "" : SEAL_STATE.getOrDefault(s, s);
    }

    public static String sealBreakReasonName(String r) {
        return r == null ? "" : SEAL_BREAK_REASON.getOrDefault(r, r);
    }

    public static String claimantTypeName(String t) {
        return t == null ? "" : CLAIMANT_TYPE.getOrDefault(t, t);
    }

    public static String claimStatusName(String s) {
        return s == null ? "" : CLAIM_STATUS.getOrDefault(s, s);
    }

    public static String reviewResultName(String r) {
        return r == null ? "" : REVIEW_RESULT.getOrDefault(r, r);
    }

    public static String reviewDecisionName(String d) {
        return d == null ? "" : REVIEW_DECISION.getOrDefault(d, d);
    }

    public static String handoverStatusName(String s) {
        return s == null ? "" : HANDOVER_STATUS.getOrDefault(s, s);
    }

    public static String handoverExceptionName(String r) {
        return r == null ? "" : HANDOVER_EXCEPTION.getOrDefault(r, r);
    }

    public static String exceptionResolutionName(String r) {
        return r == null ? "" : EXCEPTION_RESOLUTION.getOrDefault(r, r);
    }

    public static boolean isValidIdVerifyType(String t) {
        return t != null && ID_VERIFY_TYPE.containsKey(t);
    }

    public static String idVerifyTypeName(String t) {
        return t == null ? "" : ID_VERIFY_TYPE.getOrDefault(t, t);
    }

    public static String disposalResultName(String r) {
        return r == null ? "" : DISPOSAL_RESULT.getOrDefault(r, r);
    }

    public static String lostRoleName(String role) {
        return role == null ? "" : LOST_ROLE.getOrDefault(role, role);
    }

    public static String lostActionName(String action) {
        return action == null ? "" : LOST_ACTION.getOrDefault(action, action);
    }
}
