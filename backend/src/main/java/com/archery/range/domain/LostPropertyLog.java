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
 * 失物交接链（追加式账本）。登记 / 首次封存 / 查看开封 / 重封存 / 转移 / 认领申请 /
 * 审核（特征 / 时地 / 补证三结论与通过 / 补证 / 驳回）/ 进入待交还 / 异常 / 异常处置 / 双人交还 / 逾期 / 处置全程留痕。
 *
 * 只追加不修改：任何业务步骤失败都不写本链；重试以物品状态 / 封签状态为闸，不允许重复生成交接记录。
 */
@Entity
@Table(name = "lost_property_log")
public class LostPropertyLog {

    public static final String REGISTER = "REGISTER";
    public static final String FIRST_SEAL = "FIRST_SEAL";
    public static final String UNSEAL = "UNSEAL";
    public static final String RESEAL = "RESEAL";
    public static final String TRANSFER = "TRANSFER";
    public static final String CLAIM_CREATE = "CLAIM_CREATE";
    public static final String REVIEW = "REVIEW";
    public static final String NEED_MORE = "NEED_MORE";
    public static final String REJECT = "REJECT";
    public static final String APPROVE = "APPROVE";
    public static final String TO_RETURN = "TO_RETURN";
    public static final String EXCEPTION = "EXCEPTION";
    public static final String EXCEPTION_RESOLVE = "EXCEPTION_RESOLVE";
    public static final String HANDOVER_DONE = "HANDOVER_DONE";
    public static final String MARK_OVERDUE = "MARK_OVERDUE";
    public static final String DISPOSE = "DISPOSE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false)
    private LostItem item;

    @Column(name = "claim_id")
    private Long claimId;

    @Column(name = "seal_id")
    private Long sealId;

    @Column(nullable = false, length = 24)
    private String action;

    @Column(name = "before_status", length = 16)
    private String beforeStatus;

    @Column(name = "after_status", length = 16)
    private String afterStatus;

    @Column(nullable = false, length = 32)
    private String operator;

    /** FRONT 前台 / KEEPER 保管员 / COACH 教练 / MANAGER 值班经理 / MEMBER 会员 / VISITOR 访客 / SYSTEM 系统 */
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

    public LostItem getItem() {
        return item;
    }

    public void setItem(LostItem item) {
        this.item = item;
    }

    public Long getClaimId() {
        return claimId;
    }

    public void setClaimId(Long claimId) {
        this.claimId = claimId;
    }

    public Long getSealId() {
        return sealId;
    }

    public void setSealId(Long sealId) {
        this.sealId = sealId;
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
