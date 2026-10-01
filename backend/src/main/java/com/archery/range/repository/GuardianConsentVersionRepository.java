package com.archery.range.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.archery.range.domain.GuardianConsentVersion;

import jakarta.persistence.LockModeType;

public interface GuardianConsentVersionRepository extends JpaRepository<GuardianConsentVersion, Long> {

    /** 版本行悲观锁：确认 / 接管 / 放行的并发在此串行化 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from GuardianConsentVersion v where v.id = :id")
    Optional<GuardianConsentVersion> findByIdForUpdate(@Param("id") Long id);

    List<GuardianConsentVersion> findByConsentIdOrderByVersionNoDesc(Long consentId);

    Optional<GuardianConsentVersion> findByConsentIdAndVersionNo(Long consentId, Integer versionNo);

    long countByConsentId(Long consentId);
}
