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
 * 监护授权链：每个未成年会员一条链，链上串起不可变的授权版本。
 *
 * 链状态机：DRAFT 草稿 → PENDING_CONFIRM 待监护人确认 → VALID 有效 ⇄ SUSPENDED 暂停 → REVOKED 撤销。
 * 监护人确认是版本生效的唯一入口；范围 / 生效日 / 终止日 / 接管人变化只能发新版本重新确认，
 * 旧版本转 SUPERSEDED 仍可查但不得继续放行。链行是所有版本动作的串行化点（FOR UPDATE）。
 */
@Entity
@Table(name = "guardian_consent")
public class GuardianConsent {

    public static final String DRAFT = "DRAFT";
    public static final String PENDING_CONFIRM = "PENDING_CONFIRM";
    public static final String VALID = "VALID";
    public static final String SUSPENDED = "SUSPENDED";
    public static final String REVOKED = "REVOKED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "consent_no", nullable = false, unique = true, length = 24)
    private String consentNo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(name = "guardian_name", nullable = false, length = 32)
    private String guardianName;

    @Column(name = "guardian_phone", nullable = false, length = 20)
    private String guardianPhone;

    /** FATHER 父 / MOTHER 母 / OTHER 其他法定监护人 */
    @Column(name = "guardian_relation", nullable = false, length = 16)
    private String guardianRelation;

    /** DRAFT / PENDING_CONFIRM / VALID / SUSPENDED / REVOKED */
    @Column(nullable = false, length = 20)
    private String status;

    /** 当前版本 id（草稿时为草稿版本；有效 / 暂停时为现行版本） */
    @Column(name = "current_version_id")
    private Long currentVersionId;

    @Column(name = "emergency_contact_name", nullable = false, length = 32)
    private String emergencyContactName;

    @Column(name = "emergency_contact_phone", nullable = false, length = 20)
    private String emergencyContactPhone;

    @Column(length = 500)
    private String remark;

    @Column(name = "created_by", nullable = false, length = 32)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getConsentNo() {
        return consentNo;
    }

    public void setConsentNo(String consentNo) {
        this.consentNo = consentNo;
    }

    public Member getMember() {
        return member;
    }

    public void setMember(Member member) {
        this.member = member;
    }

    public String getGuardianName() {
        return guardianName;
    }

    public void setGuardianName(String guardianName) {
        this.guardianName = guardianName;
    }

    public String getGuardianPhone() {
        return guardianPhone;
    }

    public void setGuardianPhone(String guardianPhone) {
        this.guardianPhone = guardianPhone;
    }

    public String getGuardianRelation() {
        return guardianRelation;
    }

    public void setGuardianRelation(String guardianRelation) {
        this.guardianRelation = guardianRelation;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getCurrentVersionId() {
        return currentVersionId;
    }

    public void setCurrentVersionId(Long currentVersionId) {
        this.currentVersionId = currentVersionId;
    }

    public String getEmergencyContactName() {
        return emergencyContactName;
    }

    public void setEmergencyContactName(String emergencyContactName) {
        this.emergencyContactName = emergencyContactName;
    }

    public String getEmergencyContactPhone() {
        return emergencyContactPhone;
    }

    public void setEmergencyContactPhone(String emergencyContactPhone) {
        this.emergencyContactPhone = emergencyContactPhone;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
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

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
