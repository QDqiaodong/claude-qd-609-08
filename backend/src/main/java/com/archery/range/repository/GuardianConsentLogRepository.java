package com.archery.range.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.GuardianConsentLog;

public interface GuardianConsentLogRepository extends JpaRepository<GuardianConsentLog, Long> {

    List<GuardianConsentLog> findByConsentIdOrderByIdAsc(Long consentId);
}
