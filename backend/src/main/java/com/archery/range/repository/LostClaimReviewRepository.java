package com.archery.range.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.LostClaimReview;

public interface LostClaimReviewRepository extends JpaRepository<LostClaimReview, Long> {

    List<LostClaimReview> findByClaimIdOrderByReviewRoundAsc(Long claimId);
}
