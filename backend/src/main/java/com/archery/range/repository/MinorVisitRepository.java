package com.archery.range.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.archery.range.domain.MinorVisit;

import jakarta.persistence.LockModeType;

public interface MinorVisitRepository extends JpaRepository<MinorVisit, Long> {

    /** 悲观写锁：接管 / 放行 / 交还等阶段动作在此串行，多人同时确认只有一个结果生效 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from MinorVisit v where v.id = :id")
    Optional<MinorVisit> findByIdForUpdate(@Param("id") Long id);

    boolean existsByVisitNo(String visitNo);

    List<MinorVisit> findAllByOrderByIdDesc();

    List<MinorVisit> findByMemberIdOrderByIdDesc(Long memberId);

    /** 未完成交接：待接管 / 训练中 / 待交还（不允许静默关闭，也不允许开启下一次到馆） */
    List<MinorVisit> findByMemberIdAndStatusInOrderByIdDesc(Long memberId, List<String> statuses);

    List<MinorVisit> findByStatusInOrderByIdDesc(List<String> statuses);

    /** 基线漂移联动：钉住某授权版本、尚未交还的交接单（锁行，冻结与阶段动作互斥） */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from MinorVisit v where v.baselineVersionId = :versionId"
            + " and v.status in ('PENDING_PICKUP','TRAINING','PENDING_RETURN')")
    List<MinorVisit> findOpenByBaselineVersionForUpdate(@Param("versionId") Long versionId);
}
