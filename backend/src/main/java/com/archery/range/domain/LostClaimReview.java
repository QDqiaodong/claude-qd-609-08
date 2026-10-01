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
 * 认领审核结论（每轮一行，补证重新提交另起一轮，旧轮次结论保留可查）。
 *
 * 三项结论分别给出：FEATURE 特征匹配 / TIME_PLACE 时间地点吻合 / SUPPORT 补充证明；
 * 终判 APPROVE 通过 / NEED_MORE 要求补证 / REJECT 驳回；
 * 要求补证与驳回必须填写原因，下一轮审核以补充材料 + 首次申报为依据。
 */
@Entity
@Table(name = "lost_claim_review")
public class LostClaimReview {

    /** 单项结论：MATCH 匹配 / MISMATCH 不符 / UNKNOWN 待核 */
    public static final String MATCH = "MATCH";
    public static final String MISMATCH = "MISMATCH";
    public static final String UNKNOWN = "UNKNOWN";

    /** 终判 */
    public static final String APPROVE = "APPROVE";
    public static final String NEED_MORE = "NEED_MORE";
    public static final String REJECT = "REJECT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "claim_id", nullable = false)
    private LostClaim claim;

    /** 审核轮次，从 1 递增（补证重提 +1） */
    @Column(name = "review_round", nullable = false)
    private Integer reviewRound;

    @Column(name = "feature_result", nullable = false, length = 10)
    private String featureResult;

    @Column(name = "timeplace_result", nullable = false, length = 10)
    private String timeplaceResult;

    @Column(name = "support_result", nullable = false, length = 10)
    private String supportResult;

    @Column(name = "feature_note", length = 500)
    private String featureNote;

    @Column(name = "timeplace_note", length = 500)
    private String timeplaceNote;

    @Column(name = "support_note", length = 500)
    private String supportNote;

    @Column(nullable = false, length = 12)
    private String decision;

    /** 审核意见（要求补证 / 驳回时必填具体原因） */
    @Column(name = "review_note", nullable = false, length = 500)
    private String reviewNote;

    @Column(name = "reviewed_by", nullable = false, length = 32)
    private String reviewedBy;

    @Column(name = "reviewed_at", nullable = false)
    private LocalDateTime reviewedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LostClaim getClaim() {
        return claim;
    }

    public void setClaim(LostClaim claim) {
        this.claim = claim;
    }

    public Integer getReviewRound() {
        return reviewRound;
    }

    public void setReviewRound(Integer reviewRound) {
        this.reviewRound = reviewRound;
    }

    public String getFeatureResult() {
        return featureResult;
    }

    public void setFeatureResult(String featureResult) {
        this.featureResult = featureResult;
    }

    public String getTimeplaceResult() {
        return timeplaceResult;
    }

    public void setTimeplaceResult(String timeplaceResult) {
        this.timeplaceResult = timeplaceResult;
    }

    public String getSupportResult() {
        return supportResult;
    }

    public void setSupportResult(String supportResult) {
        this.supportResult = supportResult;
    }

    public String getFeatureNote() {
        return featureNote;
    }

    public void setFeatureNote(String featureNote) {
        this.featureNote = featureNote;
    }

    public String getTimeplaceNote() {
        return timeplaceNote;
    }

    public void setTimeplaceNote(String timeplaceNote) {
        this.timeplaceNote = timeplaceNote;
    }

    public String getSupportNote() {
        return supportNote;
    }

    public void setSupportNote(String supportNote) {
        this.supportNote = supportNote;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public void setReviewNote(String reviewNote) {
        this.reviewNote = reviewNote;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(String reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }
}
