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
 * 逾期失物处置登记：值班经理登记返还公安 / 捐赠 / 报废结果与接收方凭证。
 *
 * 高价值物品 / 带身份信息物品只能 POLICE 返还公安（服务层强制）；
 * 任何处置路径都必须留接收方凭证（receiver_name + receipt_no 必填）。
 */
@Entity
@Table(name = "lost_item_disposal")
public class LostItemDisposal {

    public static final String POLICE = "POLICE";
    public static final String DONATE = "DONATE";
    public static final String SCRAP = "SCRAP";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false)
    private LostItem item;

    /** POLICE 返还公安 / DONATE 捐赠 / SCRAP 报废 */
    @Column(nullable = false, length = 16)
    private String result;

    /** 接收方名称（派出所 / 受捐机构 / 报废处理单位） */
    @Column(name = "receiver_name", nullable = false, length = 64)
    private String receiverName;

    @Column(name = "receiver_contact", length = 64)
    private String receiverContact;

    /** 接收方凭证编号（回执单号等） */
    @Column(name = "receipt_no", nullable = false, length = 48)
    private String receiptNo;

    @Column(name = "receipt_note", length = 500)
    private String receiptNote;

    @Column(name = "disposed_by", nullable = false, length = 32)
    private String disposedBy;

    @Column(name = "disposed_at", nullable = false)
    private LocalDateTime disposedAt;

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

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getReceiverName() {
        return receiverName;
    }

    public void setReceiverName(String receiverName) {
        this.receiverName = receiverName;
    }

    public String getReceiverContact() {
        return receiverContact;
    }

    public void setReceiverContact(String receiverContact) {
        this.receiverContact = receiverContact;
    }

    public String getReceiptNo() {
        return receiptNo;
    }

    public void setReceiptNo(String receiptNo) {
        this.receiptNo = receiptNo;
    }

    public String getReceiptNote() {
        return receiptNote;
    }

    public void setReceiptNote(String receiptNote) {
        this.receiptNote = receiptNote;
    }

    public String getDisposedBy() {
        return disposedBy;
    }

    public void setDisposedBy(String disposedBy) {
        this.disposedBy = disposedBy;
    }

    public LocalDateTime getDisposedAt() {
        return disposedAt;
    }

    public void setDisposedAt(LocalDateTime disposedAt) {
        this.disposedAt = disposedAt;
    }
}
