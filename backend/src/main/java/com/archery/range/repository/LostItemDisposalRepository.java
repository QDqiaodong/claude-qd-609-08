package com.archery.range.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.LostItemDisposal;

public interface LostItemDisposalRepository extends JpaRepository<LostItemDisposal, Long> {
}
