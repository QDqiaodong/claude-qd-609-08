package com.archery.range.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.GuardianConsentCourse;

public interface GuardianConsentCourseRepository extends JpaRepository<GuardianConsentCourse, Long> {

    /** 版本范围只读查询：版本不可变，范围行随版本一次性写入后不再增删改 */
    List<GuardianConsentCourse> findByVersionId(Long versionId);
}
