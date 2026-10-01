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
 * 失物封签：一枚封签一行，封存时生成，开封即作废（seal_state 由 SEALED 唯一一次转为 BROKEN 并记录开封原因），
 * 重封存另发新签。每枚签固化当时的保管人 / 存放区域 / 密封状态，
 * 任何时候都能沿封签序列还原每一任保管人与每一次开封原因。
 *
 * 开封原因：INSPECT 工作人员查看 / TRANSFER 转移 / RETURN 现场交还 / RESEAL 异常重新封存前核验。
 */
@Entity
@Table(name = "lost_item_seal")
public class LostItemSeal {

    public static final String SEALED = "SEALED";
    public static final String BROKEN = "BROKEN";

    public static final String REASON_INSPECT = "INSPECT";
    public static final String REASON_TRANSFER = "TRANSFER";
    public static final String REASON_RETURN = "RETURN";
    public static final String REASON_RESEAL = "RESEAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false)
    private LostItem item;

    @Column(name = "seal_no", nullable = false, unique = true, length = 24)
    private String sealNo;

    /** 本物品第几枚封签，从 1 连续 */
    @Column(name = "seal_seq", nullable = false)
    private Integer sealSeq;

    @Column(nullable = false, length = 32)
    private String custodian;

    @Column(name = "custodian_role", nullable = false, length = 16)
    private String custodianRole;

    @Column(name = "storage_area", nullable = false, length = 32)
    private String storageArea;

    /** SEALED / BROKEN（只能 SEALED → BROKEN 一次） */
    @Column(name = "seal_state", nullable = false, length = 12)
    private String sealState;

    @Column(name = "sealed_by", nullable = false, length = 32)
    private String sealedBy;

    @Column(name = "sealed_at", nullable = false)
    private LocalDateTime sealedAt;

    @Column(name = "broken_by", length = 32)
    private String brokenBy;

    @Column(name = "broken_at")
    private LocalDateTime brokenAt;

    @Column(name = "break_reason", length = 500)
    private String breakReason;

    /** 重封存说明（从本签开封到新签封存之间的核验情况） */
    @Column(name = "reseal_note", length = 500)
    private String resealNote;

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

    public String getSealNo() {
        return sealNo;
    }

    public void setSealNo(String sealNo) {
        this.sealNo = sealNo;
    }

    public Integer getSealSeq() {
        return sealSeq;
    }

    public void setSealSeq(Integer sealSeq) {
        this.sealSeq = sealSeq;
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

    public String getStorageArea() {
        return storageArea;
    }

    public void setStorageArea(String storageArea) {
        this.storageArea = storageArea;
    }

    public String getSealState() {
        return sealState;
    }

    public void setSealState(String sealState) {
        this.sealState = sealState;
    }

    public String getSealedBy() {
        return sealedBy;
    }

    public void setSealedBy(String sealedBy) {
        this.sealedBy = sealedBy;
    }

    public LocalDateTime getSealedAt() {
        return sealedAt;
    }

    public void setSealedAt(LocalDateTime sealedAt) {
        this.sealedAt = sealedAt;
    }

    public String getBrokenBy() {
        return brokenBy;
    }

    public void setBrokenBy(String brokenBy) {
        this.brokenBy = brokenBy;
    }

    public LocalDateTime getBrokenAt() {
        return brokenAt;
    }

    public void setBrokenAt(LocalDateTime brokenAt) {
        this.brokenAt = brokenAt;
    }

    public String getBreakReason() {
        return breakReason;
    }

    public void setBreakReason(String breakReason) {
        this.breakReason = breakReason;
    }

    public String getResealNote() {
        return resealNote;
    }

    public void setResealNote(String resealNote) {
        this.resealNote = resealNote;
    }
}
