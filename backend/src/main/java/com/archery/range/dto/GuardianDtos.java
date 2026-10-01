package com.archery.range.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 未成年会员监护授权台的入参与出参。
 * 授权链 + 不可变版本 + 到馆交接两套单据共用本 DTO 文件。
 */
public final class GuardianDtos {

    private GuardianDtos() {
    }

    // ---------------- 授权链 / 版本 ----------------

    /** 允许承担现场接管责任的人员（接送人） */
    public record PickupPersonReq(
            @NotBlank(message = "请填写接管人姓名") @Size(max = 32) String name,
            @NotBlank(message = "请填写接管人证件核验标识（身份证后四位等）") @Size(max = 32) String idNo,
            @NotBlank(message = "请填写接管人手机号") String phone,
            @NotBlank(message = "请选择接管人与未成年人关系") String relation) {
    }

    /** 一版授权的完整内容：生效日 / 终止日 / 课程 / 箭道 / 器材 / 接管人 / 备注（作为整体校验） */
    public record VersionContent(
            @NotNull(message = "请选择生效日") LocalDate effectiveFrom,
            @NotNull(message = "请选择终止日") LocalDate effectiveUntil,
            @NotNull(message = "请至少选择一门允许参与的课程") List<Long> courseIds,
            @NotNull(message = "请至少选择一条可使用的箭道") List<Long> laneIds,
            @NotNull(message = "请至少选择一件可使用的器材") List<Long> equipmentIds,
            @NotNull(message = "请至少登记一名允许接管的人员") List<PickupPersonReq> pickups,
            @Size(max = 500, message = "备注不能超过 500 字") String remark) {
    }

    /** 建立授权链：未成年会员 + 监护人 + 紧急联系人 + 首版内容（落为草稿） */
    public record CreateConsentReq(
            @NotNull(message = "请选择未成年会员") Long memberId,
            @NotBlank(message = "请填写监护人姓名") @Size(max = 32) String guardianName,
            @NotBlank(message = "请填写监护人手机号") String guardianPhone,
            @NotBlank(message = "请选择监护人身份关系") String guardianRelation,
            @NotBlank(message = "请填写紧急联系人姓名") @Size(max = 32) String emergencyContactName,
            @NotBlank(message = "请填写紧急联系人电话") String emergencyContactPhone,
            @Size(max = 500, message = "必要备注不能超过 500 字") String chainRemark,
            @NotNull(message = "请填写首版授权内容") VersionContent version,
            @NotBlank(message = "请填写前台操作人") String operator) {
    }

    /** 修改草稿版内容（仅 DRAFT 版本可改；提交后要变内容必须发新版本） */
    public record UpdateDraftReq(
            @NotNull(message = "请填写授权内容") VersionContent version,
            @NotBlank(message = "请填写前台操作人") String operator) {
    }

    /** 基于当前版本发布新版本草稿（范围 / 日期 / 接管人变化的唯一入口，旧版本保留） */
    public record NewVersionReq(
            @NotNull(message = "请填写新版本授权内容") VersionContent version,
            @NotBlank(message = "请填写前台操作人") String operator) {
    }

    /** 操作人 + 原因类动作（提交 / 撤回草稿 / 暂停 / 恢复 / 撤销） */
    public record OperatorReq(
            @NotBlank(message = "请填写操作人姓名") String operator,
            @Size(max = 500, message = "原因不能超过 500 字") String reason) {
    }

    /** 监护人确认：核对身份关系后确认；拒绝时必须填写原因 */
    public record ConfirmReq(
            @NotBlank(message = "请填写现场确认人姓名") String confirmerName,
            @NotBlank(message = "请核对确认人身份关系") String confirmRelation,
            /** 确认人所确认的版本号：过期 / 重复确认据此识别并拒绝 */
            @NotNull(message = "缺少确认版本号") Integer expectedVersionNo,
            @Size(max = 500) String note,
            Boolean reject,
            @Size(max = 500, message = "拒绝原因不能超过 500 字") String rejectReason) {
    }

    // ---------------- 到馆交接 ----------------

    /** 建立到馆交接单：前台选未成年会员（系统自动挂当前有效授权；无有效授权直接拒绝建单） */
    public record CreateVisitReq(
            @NotNull(message = "请选择未成年会员") Long memberId,
            @NotBlank(message = "请填写前台操作人") String operator) {
    }

    /** 接管人身份核验信息 */
    public record PersonReq(
            @NotBlank(message = "请填写接管人姓名") String name,
            @NotBlank(message = "请核验证件并填写标识") String idNo,
            @NotBlank(message = "请填写接管人手机号") String phone,
            @NotBlank(message = "请选择关系") String relation) {
    }

    /** 接管：核对当前有效授权 + 实际接管人身份与授权接管人名单；并登记本次拟用资源（整组校验） */
    public record PickupReq(
            @NotBlank(message = "请填写前台操作人") String operator,
            @NotNull(message = "请核验收管人身份") PersonReq person,
            List<Long> courseIds,
            List<Long> laneIds,
            List<Long> equipmentIds) {
    }

    /** 训练开始前整组放行：课程 / 箭道 / 器材逐项校验当前基线，任一不覆盖整组拒绝 */
    public record ReleaseReq(
            @NotBlank(message = "请填写放行操作人（教练）") String operator,
            /** 可缩小本次放行的资源子集；为空则放行台账内全部待放行项 */
            List<Long> courseIds,
            List<Long> laneIds,
            List<Long> equipmentIds) {
    }

    /** 交还：必须由合格接管人完成；非名单人员只能由值班经理异常处置（必填原因） */
    public record ReturnReq(
            @NotBlank(message = "请填写前台操作人") String operator,
            @NotNull(message = "请核验交还人身份") PersonReq person,
            /** 值班经理异常处置：交还人不在名单内时必须为 true 且填写经理与原因 */
            Boolean managerOverride,
            @Size(max = 32) String managerName,
            @Size(max = 500, message = "异常处置原因不能超过 500 字") String overrideReason) {
    }

    /** 基线冻结后按新基线重新核对（不改变接管事实；钉到新当前版本） */
    public record RecheckReq(
            @NotBlank(message = "请填写操作人") String operator) {
    }

    /** 取消（仅待接管可取消，必填原因） */
    public record CancelReq(
            @NotBlank(message = "请填写前台操作人") String operator,
            @NotBlank(message = "取消必须填写原因") @Size(max = 500) String reason) {
    }

    // ---------------- 出参 ----------------

    public record PickupPersonView(
            String name,
            String idNo,
            String phone,
            String relation,
            String relationName) {
    }

    /** 版本详情：内容 + 范围 + 确认 / 取代 / 暂停 / 撤销留痕 */
    public record VersionView(
            Long id,
            Integer versionNo,
            String status,
            String statusName,
            LocalDate effectiveFrom,
            LocalDate effectiveUntil,
            String remark,
            String confirmedBy,
            LocalDateTime confirmedAt,
            String confirmRelation,
            String confirmNote,
            String rejectedBy,
            LocalDateTime rejectedAt,
            String rejectReason,
            Long supersededByVersionId,
            LocalDateTime supersededAt,
            String suspendedBy,
            LocalDateTime suspendedAt,
            String suspendReason,
            String revokedBy,
            LocalDateTime revokedAt,
            String revokeReason,
            String contentFingerprint,
            String createdBy,
            LocalDateTime createdAt,
            List<ScopeCourseView> courses,
            List<ScopeLaneView> lanes,
            List<ScopeEquipmentView> equipment,
            List<PickupPersonView> pickups) {
    }

    public record ScopeCourseView(Long courseId, String courseName, String coach, String levelName,
            LocalDateTime classTime, String venue) {
    }

    public record ScopeLaneView(Long laneId, String laneNo, Integer distance, String targetType) {
    }

    public record ScopeEquipmentView(Long equipmentId, String equipCode, String typeName, String brand) {
    }

    public record ConsentLogView(
            Long id,
            Long versionId,
            Integer versionNo,
            String action,
            String actionName,
            String beforeStatus,
            String beforeStatusName,
            String afterStatus,
            String afterStatusName,
            String operator,
            String role,
            String roleName,
            String detail,
            LocalDateTime createdAt) {
    }

    /** 授权链列表行 */
    public record ConsentSummary(
            Long id,
            String consentNo,
            Long memberId,
            String memberName,
            String cardNo,
            String guardianName,
            String guardianPhone,
            String guardianRelation,
            String guardianRelationName,
            String status,
            String statusName,
            Long currentVersionId,
            Integer currentVersionNo,
            LocalDate currentEffectiveFrom,
            LocalDate currentEffectiveUntil,
            String emergencyContactName,
            String emergencyContactPhone,
            String remark,
            String createdBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            Integer versionCount,
            /** 该未成年会员是否存在未完成交接 */
            Boolean openVisit) {
    }

    /** 授权链详情：链信息 + 全部版本（旧版本可查）+ 完整轨迹 */
    public record ConsentView(
            ConsentSummary summary,
            List<VersionView> versions,
            List<ConsentLogView> logs) {
    }

    public record VisitResourceView(
            Long id,
            String resourceType,
            String resourceTypeName,
            Long resourceId,
            String resourceLabel,
            String status,
            String statusName,
            String releaseOperator,
            LocalDateTime releasedAt,
            String rejectReason) {
    }

    public record VisitLogView(
            Long id,
            String action,
            String actionName,
            String stage,
            String stageName,
            String operator,
            String role,
            String roleName,
            String detail,
            LocalDateTime createdAt) {
    }

    /** 到馆交接列表行 */
    public record VisitSummary(
            Long id,
            String visitNo,
            Long memberId,
            String memberName,
            String cardNo,
            String status,
            String statusName,
            Long consentId,
            String consentNo,
            Long baselineVersionId,
            Integer baselineVersionNo,
            boolean baselineFrozen,
            String freezeReason,
            String pickupPersonName,
            String pickupPersonIdno,
            LocalDateTime pickupAt,
            LocalDateTime startedAt,
            String returnPersonName,
            Boolean qualifiedPickup,
            boolean managerOverride,
            LocalDateTime returnedAt,
            LocalDateTime createdAt) {
    }

    /** 交接详情：交接单 + 基线版本快照 + 资源放行台账 + 完整轨迹 */
    public record VisitView(
            VisitSummary summary,
            VersionView baseline,
            String currentConsentStatus,
            String currentConsentStatusName,
            Long currentVersionId,
            Integer currentVersionNo,
            List<VisitResourceView> resources,
            List<VisitLogView> logs) {
    }
}
