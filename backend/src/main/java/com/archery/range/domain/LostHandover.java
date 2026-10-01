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
 * 现场认领交接：审核通过后必须由当前保管人与另一名工作人员双人完成。
 *
 * 异常时本记录挂起（exception_*），保留原审核结果与已采集信息；异常处理后沿用同一条记录继续双人交还。
 * status 与双人确认字段（witness / receiver / handover_at）只允许写入一次：
 * 绝不让一条失败 / 异常的交接被直接改成已交还，任何异常处置都不能跳过双人交还。
 *
 * 异常原因：SEAL_DAMAGED 封签破损 / ITEM_MISMATCH 实物与登记不符 /
 * RECIPIENT_CHANGED 领取人临时变更 / CUSTODIAN_ABSENT 保管人无法到场。
 * 经理处置：RESEAL 重新封存 / RECHECK 补充核验 / TERMINATE 终止本次认领。
 */
@Entity
@Table(name = "lost_handover")
public class LostHandover {

    public static final String PENDING = "PENDING";
    public static final String DONE = "DONE";
    public static final String EXCEPTION = "EXCEPTION";

    public static final String SEAL_DAMAGED = "SEAL_DAMAGED";
    public static final String ITEM_MISMATCH = "ITEM_MISMATCH";
    public static final String RECIPIENT_CHANGED = "RECIPIENT_CHANGED";
    public static final String CUSTODIAN_ABSENT = "CUSTODIAN_ABSENT";

    public static final String RESEAL = "RESEAL";
    public static final String RECHECK = "RECHECK";
    public static final String TERMINATE = "TERMINATE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false)
    private LostItem item;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "claim_id", nullable = false)
    private LostClaim claim;

    /** 当前保管人（交还第一确认人） */
    @Column(nullable = false, length = 32)
    private String custodian;

    @Column(name = "custodian_role", nullable = false, length = 16)
    private String custodianRole;

    /** 另一名工作人员（交还第二确认人，必须不同于保管人） */
    @Column(length = 32)
    private String witness;

    @Column(name = "witness_role", length = 16)
    private String witnessRole;

    /** 实际领取人（现场核验；允许与申报认领人不同，但须经异常流程） */
    @Column(name = "receiver_name", length = 32)
    private String receiverName;

    /** 证件核验方式：ID_CARD 身份证 / MEMBER_CARD 会员卡 / DRIVING_LICENSE 驾照 / OTHER 其他证件 */
    @Column(name = "receiver_id_type", length = 16)
    private String receiverIdType;

    @Column(name = "receiver_id_no", length = 64)
    private String receiverIdNo;

    /** PENDING 待双人确认 / DONE 已完成 / EXCEPTION 异常挂起 */
    @Column(nullable = false, length = 12)
    private String status;

    @Column(name = "confirmed_by", length = 32)
    private String confirmedBy;

    @Column(name = "handover_at")
    private LocalDateTime handoverAt;

    @Column(name = "exception_reason", length = 20)
    private String exceptionReason;

    @Column(name = "exception_detail", length = 500)
    private String exceptionDetail;

    @Column(name = "exception_by", length = 32)
    private String exceptionBy;

    @Column(name = "exception_at")
    private LocalDateTime exceptionAt;

    /** RESEAL / RECHECK / TERMINATE */
    @Column(length = 20)
    private String resolution;

    @Column(name = "resolution_detail", length = 500)
    private String resolutionDetail;

    @Column(name = "resolved_by", length = 32)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

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

    public LostItem getItem() {
        return item;
    }

    public void setItem(LostItem item) {
        this.item = item;
    }

    public LostClaim getClaim() {
        return claim;
    }

    public void setClaim(LostClaim claim) {
        this.claim = claim;
    }

    public String getCustodian() {
        return custodian;
    }

    public void setCustodian(String custodian) {
        this.custodian = custodian;
    }

    public String getCustodianRole() {
        return custodianRole;
    }

    public void setCustodianRole(String custodianRole) {
        this.custodianRole = custodianRole;
    }

    public String getWitness() {
        return witness;
    }

    public void setWitness(String witness) {
        this.witness = witness;
    }

    public String getWitnessRole() {
        return witnessRole;
    }

    public void setWitnessRole(String witnessRole) {
        this.witnessRole = witnessRole;
    }

    public String getReceiverName() {
        return receiverName;
    }

    public void setReceiverName(String receiverName) {
        this.receiverName = receiverName;
    }

    public String getReceiverIdType() {
        return receiverIdType;
    }

    public void setReceiverIdType(String receiverIdType) {
        this.receiverIdType = receiverIdType;
    }

    public String getReceiverIdNo() {
        return receiverIdNo;
    }

    public void setReceiverIdNo(String receiverIdNo) {
        this.receiverIdNo = receiverIdNo;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getConfirmedBy() {
        return confirmedBy;
    }

    public void setConfirmedBy(String confirmedBy) {
        this.confirmedBy = confirmedBy;
    }

    public LocalDateTime getHandoverAt() {
        return handoverAt;
    }

    public void setHandoverAt(LocalDateTime handoverAt) {
        this.handoverAt = handoverAt;
    }

    public String getExceptionReason() {
        return exceptionReason;
    }

    public void setExceptionReason(String exceptionReason) {
        this.exceptionReason = exceptionReason;
    }

    public String getExceptionDetail() {
        return exceptionDetail;
    }

    public void setExceptionDetail(String exceptionDetail) {
        this.exceptionDetail = exceptionDetail;
    }

    public String getExceptionBy() {
        return exceptionBy;
    }

    public void setExceptionBy(String exceptionBy) {
        this.exceptionBy = exceptionBy;
    }

    public LocalDateTime getExceptionAt() {
        return exceptionAt;
    }

    public void setExceptionAt(LocalDateTime exceptionAt) {
        this.exceptionAt = exceptionAt;
    }

    public String getResolution() {
        return resolution;
    }

    public void setResolution(String resolution) {
        this.resolution = resolution;
    }

    public String getResolutionDetail() {
        return resolutionDetail;
    }

    public void setResolutionDetail(String resolutionDetail) {
        this.resolutionDetail = resolutionDetail;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public void setResolvedBy(String resolvedBy) {
        this.resolvedBy = resolvedBy;
    }

    public LocalDateTime getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(LocalDateTime resolvedAt) {
        this.resolvedAt = resolvedAt;
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
