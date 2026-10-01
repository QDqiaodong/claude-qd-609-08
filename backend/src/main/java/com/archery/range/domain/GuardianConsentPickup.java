package com.archery.range.domain;

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
 * 授权版本-允许承担现场接管责任的人员（接送人名单）。
 * 到馆接管 / 离馆交还时实际接管人必须在该名单内并核验证件；名单变化必须发新版本重新确认。
 */
@Entity
@Table(name = "guardian_consent_pickup")
public class GuardianConsentPickup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "version_id", nullable = false)
    private GuardianConsentVersion version;

    @Column(name = "person_name", nullable = false, length = 32)
    private String personName;

    /** 证件核验标识（身份证后四位等） */
    @Column(name = "id_no", nullable = false, length = 32)
    private String idNo;

    @Column(nullable = false, length = 20)
    private String phone;

    /** FATHER / MOTHER / GRANDPARENT / OTHER */
    @Column(nullable = false, length = 16)
    private String relation;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public GuardianConsentVersion getVersion() {
        return version;
    }

    public void setVersion(GuardianConsentVersion version) {
        this.version = version;
    }

    public String getPersonName() {
        return personName;
    }

    public void setPersonName(String personName) {
        this.personName = personName;
    }

    public String getIdNo() {
        return idNo;
    }

    public void setIdNo(String idNo) {
        this.idNo = idNo;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getRelation() {
        return relation;
    }

    public void setRelation(String relation) {
        this.relation = relation;
    }
}
