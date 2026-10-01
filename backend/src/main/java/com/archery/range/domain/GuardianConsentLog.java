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
 * 监护授权链 / 版本状态轨迹：建立 / 提交确认 / 监护人确认 / 拒绝 / 撤回草稿 / 发新版本 /
 * 旧版被取代 / 暂停 / 恢复 / 撤销，全程留痕。
 * beforeStatus / afterStatus + versionNo 记录每次变化前后的授权基线，可逐项还原。
 */
@Entity
@Table(name = "guardian_consent_log")
public class GuardianConsentLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "consent_id", nullable = false)
    private GuardianConsent consent;

    @Column(name = "version_id")
    private Long versionId;

    @Column(name = "version_no")
    private Integer versionNo;

    /** CREATE / SUBMIT / CONFIRM / REJECT / RECALL / NEW_VERSION / SUPERSEDE / SUSPEND / RESUME / REVOKE */
    @Column(nullable = false, length = 24)
    private String action;

    @Column(name = "before_status", length = 20)
    private String beforeStatus;

    @Column(name = "after_status", length = 20)
    private String afterStatus;

    @Column(nullable = false, length = 32)
    private String operator;

    /** FRONT 前台 / GUARDIAN 监护人 / MANAGER 值班经理 / SYSTEM 授权系统 */
    @Column(nullable = false, length = 16)
    private String role;

    @Column(length = 500)
    private String detail;

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

    public Long getVersionId() {
        return versionId;
    }

    public void setVersionId(Long versionId) {
        this.versionId = versionId;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getBeforeStatus() {
        return beforeStatus;
    }

    public void setBeforeStatus(String beforeStatus) {
        this.beforeStatus = beforeStatus;
    }

    public String getAfterStatus() {
        return afterStatus;
    }

    public void setAfterStatus(String afterStatus) {
        this.afterStatus = afterStatus;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
