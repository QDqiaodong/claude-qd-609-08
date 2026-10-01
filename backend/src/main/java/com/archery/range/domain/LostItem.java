package com.archery.range.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 失物主表（失物保管与认领交接台）。
 *
 * 登记即事实：类别 / 发现位置 / 时间 / 外观特征 / 现场照片 / 发现人一经写入，
 * 全模块再无修改这些字段的入口；物品的后续生命全部沿 {@link LostPropertyLog} 追加式交接链推进。
 *
 * 状态机：REGISTERED 待首次封存 → IN_CUSTODY 保管中 → TO_RETURN 待交还 → RETURNED 已交还；
 * 现场交接异常转 EXCEPTION 异常挂起（原审核结果保留，暂停交还）；
 * 超过保管期限无人认领 → TO_DISPOSE 待处置 → DISPOSED 已处置。
 *
 * 当前保管人 / 存放区域 / 密封状态是「最新事实」指针，历史在 {@link LostItemSeal} 与交接链中完整可还原。
 */
@Entity
@Table(name = "lost_item")
public class LostItem {

    public static final String REGISTERED = "REGISTERED";
    public static final String IN_CUSTODY = "IN_CUSTODY";
    public static final String TO_RETURN = "TO_RETURN";
    public static final String EXCEPTION = "EXCEPTION";
    public static final String RETURNED = "RETURNED";
    public static final String TO_DISPOSE = "TO_DISPOSE";
    public static final String DISPOSED = "DISPOSED";

    /** 封签密封状态 */
    public static final String SEALED = "SEALED";
    public static final String BROKEN = "BROKEN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "item_no", nullable = false, unique = true, length = 24)
    private String itemNo;

    /** 物品精确类别（内部用）：WALLET / ELECTRONIC / JEWELRY / CLOTHING / EQUIPMENT / ACCESSORY / OTHER */
    @Column(nullable = false, length = 16)
    private String category;

    /** 对外公示的模糊类别：VALUABLE 贵重物品 / ID_BEARING 带证件物品 / NORMAL 普通物品 */
    @Column(name = "public_category", nullable = false, length = 16)
    private String publicCategory;

    /** 发现位置类型：LANE 箭道 / COURSE 课程区 / PUBLIC 公共区域 */
    @Column(name = "found_location_type", nullable = false, length = 16)
    private String foundLocationType;

    @Column(name = "found_lane_id")
    private Long foundLaneId;

    @Column(name = "found_course_id")
    private Long foundCourseId;

    /** 发现区域文字（馆内区域，公示仅到此粒度） */
    @Column(name = "found_area", nullable = false, length = 64)
    private String foundArea;

    @Column(name = "found_at", nullable = false)
    private LocalDateTime foundAt;

    /** 外观特征（内部核验用，不对认领人公示） */
    @Column(nullable = false, length = 500)
    private String appearance;

    /** 现场照片引用清单（逗号分隔；仅工作人员在权限内查看） */
    @Column(name = "photo_refs", length = 500)
    private String photoRefs;

    @Column(name = "high_value_flag", nullable = false)
    private boolean highValueFlag = false;

    @Column(name = "identity_flag", nullable = false)
    private boolean identityFlag = false;

    @Column(name = "finder_name", nullable = false, length = 32)
    private String finderName;

    @Column(name = "finder_contact", length = 64)
    private String finderContact;

    /** MEMBER 会员 / STAFF 工作人员 */
    @Column(name = "finder_role", nullable = false, length = 16)
    private String finderRole;

    @Column(name = "registered_by", nullable = false, length = 32)
    private String registeredBy;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "current_custodian", length = 32)
    private String currentCustodian;

    @Column(name = "current_custodian_role", length = 16)
    private String currentCustodianRole;

    @Column(name = "current_storage_area", length = 32)
    private String currentStorageArea;

    @Column(name = "current_seal_id")
    private Long currentSealId;

    @Column(name = "seal_state", length = 12)
    private String sealState;

    /** 当前在办认领（在途期间锁定；终态后清空，可重新发起新认领） */
    @Column(name = "active_claim_id")
    private Long activeClaimId;

    @Column(name = "custody_deadline", nullable = false)
    private LocalDateTime custodyDeadline;

    @Column(name = "marked_overdue_by", length = 32)
    private String markedOverdueBy;

    @Column(name = "marked_overdue_at")
    private LocalDateTime markedOverdueAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getItemNo() {
        return itemNo;
    }

    public void setItemNo(String itemNo) {
        this.itemNo = itemNo;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getPublicCategory() {
        return publicCategory;
    }

    public void setPublicCategory(String publicCategory) {
        this.publicCategory = publicCategory;
    }

    public String getFoundLocationType() {
        return foundLocationType;
    }

    public void setFoundLocationType(String foundLocationType) {
        this.foundLocationType = foundLocationType;
    }

    public Long getFoundLaneId() {
        return foundLaneId;
    }

    public void setFoundLaneId(Long foundLaneId) {
        this.foundLaneId = foundLaneId;
    }

    public Long getFoundCourseId() {
        return foundCourseId;
    }

    public void setFoundCourseId(Long foundCourseId) {
        this.foundCourseId = foundCourseId;
    }

    public String getFoundArea() {
        return foundArea;
    }

    public void setFoundArea(String foundArea) {
        this.foundArea = foundArea;
    }

    public LocalDateTime getFoundAt() {
        return foundAt;
    }

    public void setFoundAt(LocalDateTime foundAt) {
        this.foundAt = foundAt;
    }

    public String getAppearance() {
        return appearance;
    }

    public void setAppearance(String appearance) {
        this.appearance = appearance;
    }

    public String getPhotoRefs() {
        return photoRefs;
    }

    public void setPhotoRefs(String photoRefs) {
        this.photoRefs = photoRefs;
    }

    public boolean isHighValueFlag() {
        return highValueFlag;
    }

    public void setHighValueFlag(boolean highValueFlag) {
        this.highValueFlag = highValueFlag;
    }

    public boolean isIdentityFlag() {
        return identityFlag;
    }

    public void setIdentityFlag(boolean identityFlag) {
        this.identityFlag = identityFlag;
    }

    public String getFinderName() {
        return finderName;
    }

    public void setFinderName(String finderName) {
        this.finderName = finderName;
    }

    public String getFinderContact() {
        return finderContact;
    }

    public void setFinderContact(String finderContact) {
        this.finderContact = finderContact;
    }

    public String getFinderRole() {
        return finderRole;
    }

    public void setFinderRole(String finderRole) {
        this.finderRole = finderRole;
    }

    public String getRegisteredBy() {
        return registeredBy;
    }

    public void setRegisteredBy(String registeredBy) {
        this.registeredBy = registeredBy;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCurrentCustodian() {
        return currentCustodian;
    }

    public void setCurrentCustodian(String currentCustodian) {
        this.currentCustodian = currentCustodian;
    }

    public String getCurrentCustodianRole() {
        return currentCustodianRole;
    }

    public void setCurrentCustodianRole(String currentCustodianRole) {
        this.currentCustodianRole = currentCustodianRole;
    }

    public String getCurrentStorageArea() {
        return currentStorageArea;
    }

    public void setCurrentStorageArea(String currentStorageArea) {
        this.currentStorageArea = currentStorageArea;
    }

    public Long getCurrentSealId() {
        return currentSealId;
    }

    public void setCurrentSealId(Long currentSealId) {
        this.currentSealId = currentSealId;
    }

    public String getSealState() {
        return sealState;
    }

    public void setSealState(String sealState) {
        this.sealState = sealState;
    }

    public Long getActiveClaimId() {
        return activeClaimId;
    }

    public void setActiveClaimId(Long activeClaimId) {
        this.activeClaimId = activeClaimId;
    }

    public LocalDateTime getCustodyDeadline() {
        return custodyDeadline;
    }

    public void setCustodyDeadline(LocalDateTime custodyDeadline) {
        this.custodyDeadline = custodyDeadline;
    }

    public String getMarkedOverdueBy() {
        return markedOverdueBy;
    }

    public void setMarkedOverdueBy(String markedOverdueBy) {
        this.markedOverdueBy = markedOverdueBy;
    }

    public LocalDateTime getMarkedOverdueAt() {
        return markedOverdueAt;
    }

    public void setMarkedOverdueAt(LocalDateTime markedOverdueAt) {
        this.markedOverdueAt = markedOverdueAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
