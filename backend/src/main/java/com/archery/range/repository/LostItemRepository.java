package com.archery.range.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.archery.range.domain.LostItem;

import jakarta.persistence.LockModeType;

public interface LostItemRepository extends JpaRepository<LostItem, Long> {

    /** 悲观写锁：封存 / 开封 / 转移 / 交还 / 异常 / 处置等链上动作在此串行，重试不产生重复交接记录 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from LostItem i where i.id = :id")
    Optional<LostItem> findByIdForUpdate(@Param("id") Long id);

    boolean existsByItemNo(String itemNo);

    List<LostItem> findAllByOrderByIdDesc();

    List<LostItem> findByStatusInOrderByIdDesc(List<String> statuses);

    List<LostItem> findByStatusNotInOrderByIdDesc(List<String> statuses);
}
