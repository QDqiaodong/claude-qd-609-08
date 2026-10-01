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
 * 交接单-资源放行台账：接管时声明本次要用的课程 / 箭道 / 器材（一组整体校验），
 * 训练开始前逐项放行。任一资源不被授权版本覆盖 → 整组拒绝，绝不留下部分放行。
 *
 * PENDING 待放行 → RELEASED 已放行；基线漂移时未放行项 → FROZEN（重新核对通过后可再放行）；
 * REJECTED 记录整组校验失败的拦截项与原因（重试产生新的放行尝试，不重复登记 REJECTED 行，只在轨迹留痕）。
 */
@Entity
@Table(name = "minor_visit_resource")
public class MinorVisitResource {

    public static final String COURSE = "COURSE";
    public static final String LANE = "LANE";
    public static final String EQUIPMENT = "EQUIPMENT";

    public static final String PENDING = "PENDING";
    public static final String RELEASED = "RELEASED";
    public static final String FROZEN = "FROZEN";
    public static final String REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id", nullable = false)
    private MinorVisit visit;

    /** COURSE / LANE / EQUIPMENT */
    @Column(name = "resource_type", nullable = false, length = 16)
    private String resourceType;

    @Column(name = "resource_id", nullable = false)
    private Long resourceId;

    /** 资源名称快照（课程名 / 道号 / 器材编号） */
    @Column(name = "resource_label", nullable = false, length = 64)
    private String resourceLabel;

    /** PENDING / RELEASED / FROZEN / REJECTED */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "release_operator", length = 32)
    private String releaseOperator;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

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

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public Long getResourceId() {
        return resourceId;
    }

    public void setResourceId(Long resourceId) {
        this.resourceId = resourceId;
    }

    public String getResourceLabel() {
        return resourceLabel;
    }

    public void setResourceLabel(String resourceLabel) {
        this.resourceLabel = resourceLabel;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReleaseOperator() {
        return releaseOperator;
    }

    public void setReleaseOperator(String releaseOperator) {
        this.releaseOperator = releaseOperator;
    }

    public LocalDateTime getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(LocalDateTime releasedAt) {
        this.releasedAt = releasedAt;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
