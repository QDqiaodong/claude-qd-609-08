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
 * 现场交接轨迹：建单 / 接管 / 整组放行 / 拦截 / 基线冻结 / 重新核对 / 异常处置 / 交还 / 取消，全程留痕。
 * 拦截轨迹明确写出被拦截的环节（接管 / 训练放行 / 开台 / 租借 / 交还）与原因。
 */
@Entity
@Table(name = "minor_visit_log")
public class MinorVisitLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id", nullable = false)
    private MinorVisit visit;

    /** CREATE / PICKUP / RELEASE / BLOCK / FREEZE / RECHECK / EXCEPTION / RETURN / CANCEL */
    @Column(nullable = false, length = 24)
    private String action;

    /** 动作发生时交接阶段 */
    @Column(length = 16)
    private String stage;

    @Column(nullable = false, length = 32)
    private String operator;

    /** FRONT / COACH / KEEPER / MANAGER / GUARDIAN / SYSTEM */
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

    public MinorVisit getVisit() {
        return visit;
    }

    public void setVisit(MinorVisit visit) {
        this.visit = visit;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
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
