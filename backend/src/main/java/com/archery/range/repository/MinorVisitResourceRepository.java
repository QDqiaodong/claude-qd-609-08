package com.archery.range.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.MinorVisitResource;

public interface MinorVisitResourceRepository extends JpaRepository<MinorVisitResource, Long> {

    List<MinorVisitResource> findByVisitIdOrderByIdAsc(Long visitId);
}
