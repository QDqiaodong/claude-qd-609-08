package com.archery.range.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.archery.range.domain.GuardianConsent;

import jakarta.persistence.LockModeType;

public interface GuardianConsentRepository extends JpaRepository<GuardianConsent, Long> {

    /**
     * 悲观写锁：授权链上的所有版本动作（提交 / 确认 / 发版 / 暂停 / 撤销）在此串行，
     * 多人同时确认同一份授权时只有一个结果生效。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from GuardianConsent c where c.id = :id")
    Optional<GuardianConsent> findByIdForUpdate(@Param("id") Long id);

    boolean existsByConsentNo(String consentNo);

    List<GuardianConsent> findAllByOrderByIdDesc();

    /** 未成年会员的授权链（正常只有一条；历史链也按时间倒序返回） */
    List<GuardianConsent> findByMemberIdOrderByIdDesc(Long memberId);

    /** 找会员当前生效链（存在多链时取最新，正常业务唯一） */
    List<GuardianConsent> findByMemberIdAndStatus(Long memberId, String status);

    /** 接管后基线漂移联动：找出钉住某版本的、尚未交还的交接单对应会员链……（联动在 Visit 侧查，这里提供状态计数） */
    long countByStatus(String status);
}
