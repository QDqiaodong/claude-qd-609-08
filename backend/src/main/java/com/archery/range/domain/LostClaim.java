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
 * 失物认领申请：现有会员或未建档访客均可发起。
 *
 * 一件物品同一时间至多一笔在途认领（PENDING / NEED_MORE / APPROVED / EXCEPTION）；
 * REJECTED 驳回、TERMINATED 终止为终态，终态后物品如仍在保管可由其他人发起新认领。
 * 任何路径都不能把已驳回 / 已终止的申请重新改成已交还。
 *
 * 认领申报（描述 / 遗失时间范围 / 可能出现的箭道课程 / 隐私特征）是核验依据：
 * 负责审核的值班经理只能看到完成核验所需的最少信息；教练 / 其他会员不接触这些字段。
 */
@Entity
@Table(name = "lost_claim")
public class LostClaim {

    public static final String PENDING = "PENDING";
    public static final String NEED_MORE = "NEED_MORE";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String EXCEPTION = "EXCEPTION";
    public static final String TERMINATED = "TERMINATED";
    public static final String RETURNED = "RETURNED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_no", nullable = false, unique = true, length = 24)
    private String claimNo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false)
    private LostItem item;

    /** MEMBER 现有会员 / VISITOR 未建档访客 */
    @Column(name = "claimant_type", nullable = false, length = 16)
    private String claimantType;

    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "claimant_name", nullable = false, length = 32)
    private String claimantName;

    /** 联系方式（敏感信息：仅前台 / 经理在必要环节可见） */
    @Column(name = "claimant_phone", length = 64)
    private String claimantPhone;

    /** 认领人提供的物品描述 */
    @Column(name = "claim_desc", nullable = false, length = 500)
    private String claimDesc;

    @Column(name = "lost_time_from", nullable = false)
    private LocalDateTime lostTimeFrom;

    @Column(name = "lost_time_to", nullable = false)
    private LocalDateTime lostTimeTo;

    @Column(name = "possible_lane_id")
    private Long possibleLaneId;

    @Column(name = "possible_course_id")
    private Long possibleCourseId;

    @Column(name = "possible_area", length = 64)
    private String possibleArea;

    /** 用于核验的隐私特征（不对外公示；教练 / 会员不可见） */
    @Column(name = "privacy_features", nullable = false, length = 500)
    private String privacyFeatures;

    /** 补证材料（按经理要求补充，追加留痕，不覆盖首次申报） */
    @Column(length = 500)
    private String supplementary;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "created_by", nullable = false, length = 32)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "decided_by", length = 32)
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "terminate_reason", length = 500)
    private String terminateReason;

    @Column(name = "terminated_by", length = 32)
    private String terminatedBy;

    @Column(name = "terminated_at")
    private LocalDateTime terminatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getClaimNo() {
        return claimNo;
    }

    public void setClaimNo(String claimNo) {
        this.claimNo = claimNo;
    }

    public LostItem getItem() {
        return item;
    }

    public void setItem(LostItem item) {
        this.item = item;
    }

    public String getClaimantType() {
        return claimantType;
    }

    public void setClaimantType(String claimantType) {
        this.claimantType = claimantType;
    }

    public Long getMemberId() {
        return memberId;
    }

    public void setMemberId(Long memberId) {
        this.memberId = memberId;
    }

    public String getClaimantName() {
        return claimantName;
    }

    public void setClaimantName(String claimantName) {
        this.claimantName = claimantName;
    }

    public String getClaimantPhone() {
        return claimantPhone;
    }

    public void setClaimantPhone(String claimantPhone) {
        this.claimantPhone = claimantPhone;
    }

    public String getClaimDesc() {
        return claimDesc;
    }

    public void setClaimDesc(String claimDesc) {
        this.claimDesc = claimDesc;
    }

    public LocalDateTime getLostTimeFrom() {
        return lostTimeFrom;
    }

    public void setLostTimeFrom(LocalDateTime lostTimeFrom) {
        this.lostTimeFrom = lostTimeFrom;
    }

    public LocalDateTime getLostTimeTo() {
        return lostTimeTo;
    }

    public void setLostTimeTo(LocalDateTime lostTimeTo) {
        this.lostTimeTo = lostTimeTo;
    }

    public Long getPossibleLaneId() {
        return possibleLaneId;
    }

    public void setPossibleLaneId(Long possibleLaneId) {
        this.possibleLaneId = possibleLaneId;
    }

    public Long getPossibleCourseId() {
        return possibleCourseId;
    }

    public void setPossibleCourseId(Long possibleCourseId) {
        this.possibleCourseId = possibleCourseId;
    }

    public String getPossibleArea() {
        return possibleArea;
    }

    public void setPossibleArea(String possibleArea) {
        this.possibleArea = possibleArea;
    }

    public String getPrivacyFeatures() {
        return privacyFeatures;
    }

    public void setPrivacyFeatures(String privacyFeatures) {
        this.privacyFeatures = privacyFeatures;
    }

    public String getSupplementary() {
        return supplementary;
    }

    public void setSupplementary(String supplementary) {
        this.supplementary = supplementary;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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

    public String getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }

    public LocalDateTime getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(LocalDateTime decidedAt) {
        this.decidedAt = decidedAt;
    }

    public String getTerminateReason() {
        return terminateReason;
    }

    public void setTerminateReason(String terminateReason) {
        this.terminateReason = terminateReason;
    }

    public String getTerminatedBy() {
        return terminatedBy;
    }

    public void setTerminatedBy(String terminatedBy) {
        this.terminatedBy = terminatedBy;
    }

    public LocalDateTime getTerminatedAt() {
        return terminatedAt;
    }

    public void setTerminatedAt(LocalDateTime terminatedAt) {
        this.terminatedAt = terminatedAt;
    }
}
