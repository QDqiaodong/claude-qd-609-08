package com.archery.range.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.archery.range.domain.LostItemSeal;

import jakarta.persistence.LockModeType;

public interface LostItemSealRepository extends JpaRepository<LostItemSeal, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from LostItemSeal s where s.id = :id")
    Optional<LostItemSeal> findByIdForUpdate(@Param("id") Long id);

    boolean existsBySealNo(String sealNo);

    List<LostItemSeal> findByItemIdOrderBySealSeqAsc(Long itemId);

    int countByItemId(Long itemId);
}
