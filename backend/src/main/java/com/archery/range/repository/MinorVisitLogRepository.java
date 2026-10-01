package com.archery.range.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.MinorVisitLog;

public interface MinorVisitLogRepository extends JpaRepository<MinorVisitLog, Long> {

    List<MinorVisitLog> findByVisitIdOrderByIdAsc(Long visitId);
}
