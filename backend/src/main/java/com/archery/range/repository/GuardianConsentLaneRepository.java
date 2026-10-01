package com.archery.range.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.GuardianConsentLane;

public interface GuardianConsentLaneRepository extends JpaRepository<GuardianConsentLane, Long> {

    List<GuardianConsentLane> findByVersionId(Long versionId);
}
