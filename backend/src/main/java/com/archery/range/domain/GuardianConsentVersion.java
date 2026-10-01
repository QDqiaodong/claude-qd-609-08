package com.archery.range.domain;

import java.time.LocalDate;
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
 * 监护授权版本：版本内容（生效日 / 终止日 / 课程 / 箭道 / 器材 / 接管人 / 备注）一经建立即冻结，
 * 不提供任何就地改写入口；要变内容只能发布新版本。
 *
 * 版本状态：DRAFT 草稿 → PENDING_CONFIRM 待确认 → VALID 有效；
 * VALID 可随链 SUSPENDED 暂停 / REVOKED 撤销；新确认的版本会把旧 VALID 版本置为 SUPERSEDED；
 * 监护人拒绝确认 → REJECTED（保留可查，前台需改内容时另发新版本）。
 * 到馆交接钉住的基线版本即使之后被取代 / 暂停 / 撤销，历史事实也不改写。
 */
@Entity
@Table(name = "guardian_consent_version")
public class GuardianConsentVersion {

    public static final String DRAFT = "DRAFT";
    public static final String PENDING_CONFIRM = "PENDING_CONFIRM";
    public static final String VALID = "VALID";
    public static final String SUPERSEDED = "SUPERSEDED";
    public static final String SUSPENDED = "SUSPENDED";
    public static final String REVOKED = "REVOKED";
    public static final String REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "consent_id", nullable = false)
    private GuardianConsent consent;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    /** DRAFT / PENDING_CONFIRM / VALID / SUPERSEDED / SUSPENDED / REVOKED / REJECTED */
    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_until", nullable = false)
    private LocalDate effectiveUntil;

    @Column(name = "version_remark", length = 500)
    private String versionRemark;

    @Column(name = "confirmed_by", length = 32)
    private String confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "confirm_relation", length = 16)
    private String confirmRelation;

    @Column(name = "confirm_note", length = 500)
    private String confirmNote;

    @Column(name = "rejected_by", length = 32)
    private String rejectedBy;

    @Column(name = "rejected_at")
    private LocalDateTime rejectedAt;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    @Column(name = "superseded_by_version_id")
    private Long supersededByVersionId;

    @Column(name = "superseded_at")
    private LocalDateTime supersededAt;

    @Column(name = "suspended_by", length = 32)
    private String suspendedBy;

    @Column(name = "suspended_at")
    private LocalDateTime suspendedAt;

    @Column(name = "suspend_reason", length = 500)
    private String suspendReason;

    @Column(name = "revoked_by", length = 32)
    private String revokedBy;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revoke_reason", length = 500)
    private String revokeReason;

    /** 版本内容指纹：确认 / 接管 / 放行时比对，防止「同版本号但内容已变」 */
    @Column(name = "content_fingerprint", nullable = false, length = 64)
    private String contentFingerprint;

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

    public GuardianConsent getConsent() {
        return consent;
    }

    public void setConsent(GuardianConsent consent) {
        this.consent = consent;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(LocalDate effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public LocalDate getEffectiveUntil() {
        return effectiveUntil;
    }

    public void setEffectiveUntil(LocalDate effectiveUntil) {
        this.effectiveUntil = effectiveUntil;
    }

    public String getVersionRemark() {
        return versionRemark;
    }

    public void setVersionRemark(String versionRemark) {
        this.versionRemark = versionRemark;
    }

    public String getConfirmedBy() {
        return confirmedBy;
    }

    public void setConfirmedBy(String confirmedBy) {
        this.confirmedBy = confirmedBy;
    }

    public LocalDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(LocalDateTime confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public String getConfirmRelation() {
        return confirmRelation;
    }

    public void setConfirmRelation(String confirmRelation) {
        this.confirmRelation = confirmRelation;
    }

    public String getConfirmNote() {
        return confirmNote;
    }

    public void setConfirmNote(String confirmNote) {
        this.confirmNote = confirmNote;
    }

    public String getRejectedBy() {
        return rejectedBy;
    }

    public void setRejectedBy(String rejectedBy) {
        this.rejectedBy = rejectedBy;
    }

    public LocalDateTime getRejectedAt() {
        return rejectedAt;
    }

    public void setRejectedAt(LocalDateTime rejectedAt) {
        this.rejectedAt = rejectedAt;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    public Long getSupersededByVersionId() {
        return supersededByVersionId;
    }

    public void setSupersededByVersionId(Long supersededByVersionId) {
        this.supersededByVersionId = supersededByVersionId;
    }

    public LocalDateTime getSupersededAt() {
        return supersededAt;
    }

    public void setSupersededAt(LocalDateTime supersededAt) {
        this.supersededAt = supersededAt;
    }

    public String getSuspendedBy() {
        return suspendedBy;
    }

    public void setSuspendedBy(String suspendedBy) {
        this.suspendedBy = suspendedBy;
    }

    public LocalDateTime getSuspendedAt() {
        return suspendedAt;
    }

    public void setSuspendedAt(LocalDateTime suspendedAt) {
        this.suspendedAt = suspendedAt;
    }

    public String getSuspendReason() {
        return suspendReason;
    }

    public void setSuspendReason(String suspendReason) {
        this.suspendReason = suspendReason;
    }

    public String getRevokedBy() {
        return revokedBy;
    }

    public void setRevokedBy(String revokedBy) {
        this.revokedBy = revokedBy;
    }

    public LocalDateTime getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(LocalDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }

    public String getRevokeReason() {
        return revokeReason;
    }

    public void setRevokeReason(String revokeReason) {
        this.revokeReason = revokeReason;
    }

    public String getContentFingerprint() {
        return contentFingerprint;
    }

    public void setContentFingerprint(String contentFingerprint) {
        this.contentFingerprint = contentFingerprint;
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
