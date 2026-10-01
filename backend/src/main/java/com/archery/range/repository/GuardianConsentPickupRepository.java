package com.archery.range.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.GuardianConsentPickup;

public interface GuardianConsentPickupRepository extends JpaRepository<GuardianConsentPickup, Long> {

    List<GuardianConsentPickup> findByVersionId(Long versionId);
}
