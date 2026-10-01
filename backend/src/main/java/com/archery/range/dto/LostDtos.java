package com.archery.range.dto;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 失物保管与认领交接台的入参与出参。
 *
 * 出参按角色做最小化：
 * - 公示视图（任何访客）：只有模糊类别、发现日期、馆内区域，绝无足以直接猜中答案的细节；
 * - 教练视图：保管链（编号 / 状态 / 区域 / 封签态）可见，但现场照片、认领证据与联系方式不可见；
 * - 前台视图：登记细节、保管链、认领摘要（不含隐私特征原文）可见；
 * - 经理审核视图：仅在审核场景给出完成核验所需的最少信息（物品外观 + 认领申报，照片不默认展示）。
 */
public final class LostDtos {

    private LostDtos() {
    }

    // ---------------- 入参：登记与首次封存 ----------------

    /** 失物登记（发现人登记：类别 / 位置 / 时间 / 外观 / 照片） */
    public record RegisterItemReq(
            @NotBlank(message = "请选择物品类别") String category,
            @NotBlank(message = "请选择发现位置类型") String foundLocationType,
            Long foundLaneId,
            Long foundCourseId,
            @NotBlank(message = "请填写发现的馆内区域") @Size(max = 64) String foundArea,
            @NotNull(message = "请选择发现时间") LocalDateTime foundAt,
            @NotBlank(message = "请填写外观特征") @Size(max = 500) String appearance,
            @Size(max = 500) String photoRefs,
            Boolean highValueFlag,
            Boolean identityFlag,
            @NotBlank(message = "请填写发现人姓名") @Size(max = 32) String finderName,
            @Size(max = 64) String finderContact,
            @NotBlank(message = "请选择发现人身份") String finderRole,
            @NotBlank(message = "请填写登记前台") String operator) {
    }

    /** 值班前台生成封签并完成首次交接（登记后一步封存） */
    public record FirstSealReq(
            @NotBlank(message = "请填写保管人") String custodian,
            @NotBlank(message = "请选择保管人岗位") String custodianRole,
            @NotBlank(message = "请选择存放区域") String storageArea,
            @NotBlank(message = "请填写操作前台") String operator) {
    }

    /** 工作人员查看（必须开封登记原因；查看后须重封存） */
    public record InspectReq(
            @NotBlank(message = "请填写查看人") String operator,
            @NotBlank(message = "请选择查看人岗位") String operatorRole,
            /** 固定为 INSPECT（工作人员查看）；转移 / 交还 / 重封存核验开封由对应流程自动登记 */
            String reason,
            @NotBlank(message = "请填写开封查看原因说明") @Size(max = 500) String reasonNote) {
    }

    /** 查看后重新封存 */
    public record ResealReq(
            @NotBlank(message = "请填写封存人") String custodian,
            @NotBlank(message = "请选择保管人岗位") String custodianRole,
            @NotBlank(message = "请选择存放区域") String storageArea,
            @NotBlank(message = "请填写操作人") String operator,
            @NotBlank(message = "请填写重封存核验说明") @Size(max = 500) String note) {
    }

    /** 保管转移（封好的物品交下一任保管人：因转移先开封旧签，核验后由新保管人重封存） */
    public record TransferReq(
            @NotBlank(message = "请填写新保管人") String toCustodian,
            @NotBlank(message = "请选择新保管人岗位") String toCustodianRole,
            @NotBlank(message = "请选择新存放区域") String toStorageArea,
            @NotBlank(message = "请填写移交操作人") String operator,
            @NotBlank(message = "请选择移交操作人岗位") String operatorRole,
            @NotBlank(message = "请填写转移原因与核验情况") @Size(max = 500) String note) {
    }

    // ---------------- 入参：认领 ----------------

    /** 前台录入认领申报（会员或未建档访客） */
    public record CreateClaimReq(
            @NotBlank(message = "请选择认领人类型（会员 / 访客）") String claimantType,
            Long memberId,
            @NotBlank(message = "请填写认领人姓名") @Size(max = 32) String claimantName,
            @Size(max = 64) String claimantPhone,
            @NotBlank(message = "请填写认领人提供的物品描述") @Size(max = 500) String claimDesc,
            @NotNull(message = "请选择自述遗失时间范围（起）") LocalDateTime lostTimeFrom,
            @NotNull(message = "请选择自述遗失时间范围（止）") LocalDateTime lostTimeTo,
            Long possibleLaneId,
            Long possibleCourseId,
            @Size(max = 64) String possibleArea,
            @NotBlank(message = "请填写用于核验的隐私特征") @Size(max = 500) String privacyFeatures,
            @NotBlank(message = "请填写录入前台") String operator) {
    }

    /** 认领人按经理要求补证（追加，不覆盖首次申报） */
    public record SupplementReq(
            @NotBlank(message = "请填写补充证明材料") @Size(max = 500) String supplementary,
            @NotBlank(message = "请填写录入前台") String operator) {
    }

    /** 值班经理审核：三项分项结论 + 终判（通过 / 补证 / 驳回） */
    public record ReviewReq(
            @NotBlank(message = "请给出特征匹配结论") String featureResult,
            @NotBlank(message = "请给出时间地点吻合结论") String timeplaceResult,
            @NotBlank(message = "请给出补充证明结论") String supportResult,
            @Size(max = 500) String featureNote,
            @Size(max = 500) String timeplaceNote,
            @Size(max = 500) String supportNote,
            @NotBlank(message = "请给出终判（通过 / 补证 / 驳回）") String decision,
            @NotBlank(message = "请填写审核意见") @Size(max = 500) String reviewNote,
            /** 是否调阅现场照片（默认不给经理看照片，确需核看须明示，仍只给经理） */
            Boolean includePhoto,
            @NotBlank(message = "请填写审核值班经理") String manager) {
    }

    // ---------------- 入参：现场交还与异常 ----------------

    /** 实际领取人证件核验信息 */
    public record ReceiverReq(
            @NotBlank(message = "请填写实际领取人姓名") @Size(max = 32) String name,
            @NotBlank(message = "请选择证件核验方式") String idType,
            @NotBlank(message = "请核验证件并填写标识") @Size(max = 64) String idNo) {
    }

    /** 双人现场交还：当前保管人 + 另一名工作人员共同确认 */
    public record HandoverCompleteReq(
            @NotBlank(message = "请填写当前保管人（第一确认人）") String custodian,
            @NotBlank(message = "请选择保管人岗位") String custodianRole,
            @NotBlank(message = "请填写另一名工作人员（第二确认人）") String witness,
            @NotBlank(message = "请选择见证工作人员岗位") String witnessRole,
            @NotNull(message = "请核验实际领取人证件") ReceiverReq receiver,
            @NotBlank(message = "请填写主持交还的前台") String operator) {
    }

    /** 现场交接异常上报（暂停交还，原审核结果保留） */
    public record HandoverExceptionReq(
            @NotBlank(message = "请选择异常原因") String reason,
            @NotBlank(message = "请填写异常情况说明") @Size(max = 500) String detail,
            @NotBlank(message = "请填写上报操作人") String operator,
            @NotBlank(message = "请选择上报人岗位") String operatorRole) {
    }

    /** 值班经理异常处置：重新封存 / 补充核验 / 终止本次认领 */
    public record ResolveExceptionReq(
            @NotBlank(message = "请选择处置方式（重新封存 / 补充核验 / 终止认领）") String resolution,
            @NotBlank(message = "请填写处置说明") @Size(max = 500) String detail,
            @NotBlank(message = "请填写值班经理") String manager,
            /** RESEAL 时给定新封签的保管人 / 存放区域（仍是双人交还前的重新封存） */
            String custodian,
            String custodianRole,
            String storageArea) {
    }

    // ---------------- 入参：逾期处置 ----------------

    public record MarkOverdueReq(
            @NotBlank(message = "请填写值班经理") String manager) {
    }

    public record DisposeReq(
            @NotBlank(message = "请选择处置结果（返还公安 / 捐赠 / 报废）") String result,
            @NotBlank(message = "请填写接收方名称") @Size(max = 64) String receiverName,
            @Size(max = 64) String receiverContact,
            @NotBlank(message = "请填写接收方凭证编号") @Size(max = 48) String receiptNo,
            @Size(max = 500) String receiptNote,
            @NotBlank(message = "请填写处置值班经理") String manager) {
    }

    // ---------------- 出参 ----------------

    /** 对认领人公示：模糊类别 + 发现日期 + 馆内区域（无任何可直接猜中答案的细节） */
    public record PublicItemView(
            Long id,
            String itemNo,
            String publicCategory,
            String publicCategoryName,
            String foundDate,
            String areaName) {
    }

    public record SealView(
            Long id,
            String sealNo,
            Integer sealSeq,
            String custodian,
            String custodianRole,
            String storageArea,
            String storageAreaName,
            String sealState,
            String sealStateName,
            String sealedBy,
            LocalDateTime sealedAt,
            String brokenBy,
            LocalDateTime brokenAt,
            String breakReason,
            String breakReasonName,
            String resealNote) {
    }

    public record ChainLogView(
            Long id,
            Long claimId,
            Long sealId,
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

    /** 审核轮次结论（分项 + 终判） */
    public record ReviewView(
            Long id,
            Integer reviewRound,
            String featureResult,
            String featureResultName,
            String timeplaceResult,
            String timeplaceResultName,
            String supportResult,
            String supportResultName,
            String featureNote,
            String timeplaceNote,
            String supportNote,
            String decision,
            String decisionName,
            String reviewNote,
            String reviewedBy,
            LocalDateTime reviewedAt) {
    }

    /**
     * 认领视图。敏感字段按角色填充：
     * privacyFeatures / claimantPhone 仅经理（审核包）与前台（联系用，联系方式）可见；
     * 教练与公示场景为 null。
     */
    public record ClaimView(
            Long id,
            String claimNo,
            Long itemId,
            String claimantType,
            String claimantTypeName,
            Long memberId,
            String claimantName,
            String claimantPhone,
            String claimDesc,
            LocalDateTime lostTimeFrom,
            LocalDateTime lostTimeTo,
            Long possibleLaneId,
            String possibleLaneNo,
            Long possibleCourseId,
            String possibleCourseName,
            String possibleArea,
            String privacyFeatures,
            String supplementary,
            String status,
            String statusName,
            String createdBy,
            LocalDateTime createdAt,
            String decidedBy,
            LocalDateTime decidedAt,
            String terminateReason,
            String terminatedBy,
            LocalDateTime terminatedAt,
            List<ReviewView> reviews) {
    }

    public record HandoverView(
            Long id,
            String custodian,
            String custodianRole,
            String witness,
            String witnessRole,
            String receiverName,
            String receiverIdType,
            String receiverIdTypeName,
            String receiverIdNo,
            String status,
            String statusName,
            String confirmedBy,
            LocalDateTime handoverAt,
            String exceptionReason,
            String exceptionReasonName,
            String exceptionDetail,
            String exceptionBy,
            LocalDateTime exceptionAt,
            String resolution,
            String resolutionName,
            String resolutionDetail,
            String resolvedBy,
            LocalDateTime resolvedAt,
            LocalDateTime createdAt) {
    }

    public record DisposalView(
            Long id,
            String result,
            String resultName,
            String receiverName,
            String receiverContact,
            String receiptNo,
            String receiptNote,
            String disposedBy,
            LocalDateTime disposedAt) {
    }

    /**
     * 失物详情（角色感知）：photoRefs / appearance / finderContact 按角色裁剪，
     * claim 为当前在办认领（非经理角色隐私字段脱敏），pastClaims 仅给编号 / 状态。
     */
    public record ItemView(
            Long id,
            String itemNo,
            String category,
            String categoryName,
            String publicCategory,
            String publicCategoryName,
            String foundLocationType,
            String foundLocationTypeName,
            Long foundLaneId,
            String foundLaneNo,
            Long foundCourseId,
            String foundCourseName,
            String foundArea,
            LocalDateTime foundAt,
            String appearance,
            List<String> photoRefs,
            Boolean photoUnlocked,
            boolean highValueFlag,
            boolean identityFlag,
            String finderName,
            String finderContact,
            String finderRole,
            String registeredBy,
            String status,
            String statusName,
            String currentCustodian,
            String currentCustodianRole,
            String currentStorageArea,
            String currentStorageAreaName,
            Long currentSealId,
            String sealState,
            String sealStateName,
            LocalDateTime custodyDeadline,
            boolean overdue,
            String markedOverdueBy,
            LocalDateTime markedOverdueAt,
            LocalDateTime createdAt,
            Long activeClaimId,
            ClaimView activeClaim,
            List<ClaimView> claims,
            List<SealView> seals,
            HandoverView handover,
            DisposalView disposal,
            List<ChainLogView> logs,
            /** 当前角色在当前状态下可执行的动作（页面只渲染这些按钮） */
            List<String> allowedActions,
            /** 本视图所依据的查看角色 */
            String viewerRole) {
    }

    /** 失物台账列表行（工作人员，内部列表；不对外） */
    public record ItemRow(
            Long id,
            String itemNo,
            String category,
            String categoryName,
            String publicCategory,
            String publicCategoryName,
            String foundArea,
            LocalDateTime foundAt,
            boolean highValueFlag,
            boolean identityFlag,
            String status,
            String statusName,
            String currentCustodian,
            String currentStorageAreaName,
            String sealState,
            String sealStateName,
            Long activeClaimId,
            String activeClaimStatus,
            String activeClaimStatusName,
            LocalDateTime custodyDeadline,
            boolean overdue,
            List<String> allowedActions) {
    }
}
