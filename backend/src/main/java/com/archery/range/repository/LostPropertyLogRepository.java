package com.archery.range.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.LostPropertyLog;

public interface LostPropertyLogRepository extends JpaRepository<LostPropertyLog, Long> {

    List<LostPropertyLog> findByItemIdOrderByIdAsc(Long itemId);
}
