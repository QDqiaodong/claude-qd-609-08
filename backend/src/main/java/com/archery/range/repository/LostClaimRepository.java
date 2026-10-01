package com.archery.range.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.LostClaim;

public interface LostClaimRepository extends JpaRepository<LostClaim, Long> {

    boolean existsByClaimNo(String claimNo);

    List<LostClaim> findByItemIdOrderByIdDesc(Long itemId);

    List<LostClaim> findAllByOrderByIdDesc();

    List<LostClaim> findByStatusInOrderByIdDesc(List<String> statuses);
}
