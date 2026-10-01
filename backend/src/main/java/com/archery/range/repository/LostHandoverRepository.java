package com.archery.range.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.archery.range.domain.LostHandover;

import jakarta.persistence.LockModeType;

public interface LostHandoverRepository extends JpaRepository<LostHandover, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from LostHandover h where h.id = :id")
    Optional<LostHandover> findByIdForUpdate(@Param("id") Long id);

    /** 一件物品可能因「终止后重新认领」存在多笔交接，业务上始终取最新一笔 */
    Optional<LostHandover> findTopByItemIdOrderByIdDesc(Long itemId);

    List<LostHandover> findByItemIdOrderByIdDesc(Long itemId);
}
