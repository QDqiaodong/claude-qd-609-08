package com.archery.range.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 未成年会员每次到馆的现场交接记录（一到馆一单）。
 *
 * 阶段：PENDING_PICKUP 待接管 → TRAINING 训练中 → PENDING_RETURN 待交还 → RETURNED 已交还；
 * 未接管的单据可 CANCELED 取消，已接管的不能取消也不能静默关闭，必须完成安全交还。
 *
 * 接管时把当时的有效授权版本钉为现场基线（baselineVersionId + 指纹）。之后授权换版 / 暂停 / 撤销
 * 不改写本单事实，只置 baselineFrozen=1：尚未开始的课程 / 箭道 / 器材动作立即冻结，
 * 工作人员须按新基线重新核对（rebind）；已开始的训练留下异常处置轨迹直到安全交还。
 */
@Entity
@Table(name = "minor_visit")
public class MinorVisit {

    public static final String PENDING_PICKUP = "PENDING_PICKUP";
    public static final String TRAINING = "TRAINING";
    public static final String PENDING_RETURN = "PENDING_RETURN";
    public static final String RETURNED = "RETURNED";
    public static final String CANCELED = "CANCELED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "visit_no", nullable = false, unique = true, length = 24)
    private String visitNo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    /** PENDING_PICKUP / TRAINING / PENDING_RETURN / RETURNED / CANCELED */
    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "consent_id", nullable = false)
    private Long consentId;

    /** 接管时钉住的授权版本（历史事实，不随后续换版改写） */
    @Column(name = "baseline_version_id", nullable = false)
    private Long baselineVersionId;

    @Column(name = "baseline_fingerprint", nullable = false, length = 64)
    private String baselineFingerprint;

    /** 基线漂移标记：1 时未开始动作全部冻结，需重新核对 */
    @Column(name = "baseline_frozen", nullable = false)
    private boolean baselineFrozen = false;

    @Column(name = "freeze_reason", length = 500)
    private String freezeReason;

    @Column(name = "frozen_at")
    private LocalDateTime frozenAt;

    // ---------------- 接管 ----------------
    @Column(name = "pickup_person_name", length = 32)
    private String pickupPersonName;

    @Column(name = "pickup_person_idno", length = 32)
    private String pickupPersonIdno;

    @Column(name = "pickup_person_phone", length = 20)
    private String pickupPersonPhone;

    @Column(name = "pickup_person_relation", length = 16)
    private String pickupPersonRelation;

    @Column(name = "pickup_operator", length = 32)
    private String pickupOperator;

    @Column(name = "pickup_at")
    private LocalDateTime pickupAt;

    // ---------------- 训练开始 ----------------
    @Column(name = "start_operator", length = 32)
    private String startOperator;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    // ---------------- 交还 ----------------
    @Column(name = "return_person_name", length = 32)
    private String returnPersonName;

    @Column(name = "return_person_idno", length = 32)
    private String returnPersonIdno;

    @Column(name = "return_person_phone", length = 20)
    private String returnPersonPhone;

    @Column(name = "return_person_relation", length = 16)
    private String returnPersonRelation;

    /** 交还人是否在基线版本接管人名单内 */
    @Column(name = "qualified_pickup")
    private Boolean qualifiedPickup;

    /** 值班经理异常处置放行（非名单接管人，必填原因 + 轨迹） */
    @Column(name = "manager_override", nullable = false)
    private boolean managerOverride = false;

    @Column(name = "override_manager", length = 32)
    private String overrideManager;

    @Column(name = "override_reason", length = 500)
    private String overrideReason;

    @Column(name = "return_operator", length = 32)
    private String returnOperator;

    @Column(name = "returned_at")
    private LocalDateTime returnedAt;

    // ---------------- 取消 ----------------
    @Column(name = "cancel_operator", length = 32)
    private String cancelOperator;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    @Column(name = "created_by", nullable = false, length = 32)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getVisitNo() {
        return visitNo;
    }

    public void setVisitNo(String visitNo) {
        this.visitNo = visitNo;
    }

    public Member getMember() {
        return member;
    }

    public void setMember(Member member) {
        this.member = member;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getConsentId() {
        return consentId;
    }

    public void setConsentId(Long consentId) {
        this.consentId = consentId;
    }

    public Long getBaselineVersionId() {
        return baselineVersionId;
    }

    public void setBaselineVersionId(Long baselineVersionId) {
        this.baselineVersionId = baselineVersionId;
    }

    public String getBaselineFingerprint() {
        return baselineFingerprint;
    }

    public void setBaselineFingerprint(String baselineFingerprint) {
        this.baselineFingerprint = baselineFingerprint;
    }

    public boolean isBaselineFrozen() {
        return baselineFrozen;
    }

    public void setBaselineFrozen(boolean baselineFrozen) {
        this.baselineFrozen = baselineFrozen;
    }

    public String getFreezeReason() {
        return freezeReason;
    }

    public void setFreezeReason(String freezeReason) {
        this.freezeReason = freezeReason;
    }

    public LocalDateTime getFrozenAt() {
        return frozenAt;
    }

    public void setFrozenAt(LocalDateTime frozenAt) {
        this.frozenAt = frozenAt;
    }

    public String getPickupPersonName() {
        return pickupPersonName;
    }

    public void setPickupPersonName(String pickupPersonName) {
        this.pickupPersonName = pickupPersonName;
    }

    public String getPickupPersonIdno() {
        return pickupPersonIdno;
    }

    public void setPickupPersonIdno(String pickupPersonIdno) {
        this.pickupPersonIdno = pickupPersonIdno;
    }

    public String getPickupPersonPhone() {
        return pickupPersonPhone;
    }

    public void setPickupPersonPhone(String pickupPersonPhone) {
        this.pickupPersonPhone = pickupPersonPhone;
    }

    public String getPickupPersonRelation() {
        return pickupPersonRelation;
    }

    public void setPickupPersonRelation(String pickupPersonRelation) {
        this.pickupPersonRelation = pickupPersonRelation;
    }

    public String getPickupOperator() {
        return pickupOperator;
    }

    public void setPickupOperator(String pickupOperator) {
        this.pickupOperator = pickupOperator;
    }

    public LocalDateTime getPickupAt() {
        return pickupAt;
    }

    public void setPickupAt(LocalDateTime pickupAt) {
        this.pickupAt = pickupAt;
    }

    public String getStartOperator() {
        return startOperator;
    }

    public void setStartOperator(String startOperator) {
        this.startOperator = startOperator;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public String getReturnPersonName() {
        return returnPersonName;
    }

    public void setReturnPersonName(String returnPersonName) {
        this.returnPersonName = returnPersonName;
    }

    public String getReturnPersonIdno() {
        return returnPersonIdno;
    }

    public void setReturnPersonIdno(String returnPersonIdno) {
        this.returnPersonIdno = returnPersonIdno;
    }

    public String getReturnPersonPhone() {
        return returnPersonPhone;
    }

    public void setReturnPersonPhone(String returnPersonPhone) {
        this.returnPersonPhone = returnPersonPhone;
    }

    public String getReturnPersonRelation() {
        return returnPersonRelation;
    }

    public void setReturnPersonRelation(String returnPersonRelation) {
        this.returnPersonRelation = returnPersonRelation;
    }

    public Boolean getQualifiedPickup() {
        return qualifiedPickup;
    }

    public void setQualifiedPickup(Boolean qualifiedPickup) {
        this.qualifiedPickup = qualifiedPickup;
    }

    public boolean isManagerOverride() {
        return managerOverride;
    }

    public void setManagerOverride(boolean managerOverride) {
        this.managerOverride = managerOverride;
    }

    public String getOverrideManager() {
        return overrideManager;
    }

    public void setOverrideManager(String overrideManager) {
        this.overrideManager = overrideManager;
    }

    public String getOverrideReason() {
        return overrideReason;
    }

    public void setOverrideReason(String overrideReason) {
        this.overrideReason = overrideReason;
    }

    public String getReturnOperator() {
        return returnOperator;
    }

    public void setReturnOperator(String returnOperator) {
        this.returnOperator = returnOperator;
    }

    public LocalDateTime getReturnedAt() {
        return returnedAt;
    }

    public void setReturnedAt(LocalDateTime returnedAt) {
        this.returnedAt = returnedAt;
    }

    public String getCancelOperator() {
        return cancelOperator;
    }

    public void setCancelOperator(String cancelOperator) {
        this.cancelOperator = cancelOperator;
    }

    public LocalDateTime getCanceledAt() {
        return canceledAt;
    }

    public void setCanceledAt(LocalDateTime canceledAt) {
        this.canceledAt = canceledAt;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public void setCancelReason(String cancelReason) {
        this.cancelReason = cancelReason;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
