package com.archery.range.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.GuardianConsentEquipment;

public interface GuardianConsentEquipmentRepository extends JpaRepository<GuardianConsentEquipment, Long> {

    List<GuardianConsentEquipment> findByVersionId(Long versionId);
}
