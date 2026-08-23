package com.axonect.ee.enterpriseintegration.domain.repository;

import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Optional;


public interface DownloadReportRepository extends JpaRepository<DownloadReport, Long> {

    @Query("""
        SELECT dr FROM DownloadReport dr
        WHERE (:createdBy IS NULL OR dr.createdBy = :createdBy)
          AND (:reportType IS NULL OR dr.reportType = :reportType)
          AND (:reportStatus IS NULL OR dr.reportStatus = :reportStatus)
          AND (:startDate IS NULL OR dr.createdAt >= :startDate)
          AND (:endDate IS NULL OR dr.createdAt <= :endDate)
    """)
    Page<DownloadReport> findFilteredReports(
            @Param("createdBy") String createdBy,
            @Param("reportType") String reportType,
            @Param("reportStatus") String reportStatus,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            Pageable pageable
    );

    @Query("SELECT COUNT(r) FROM DownloadReport r WHERE r.reportStatus = 'Processing'")
    long countProcessingReports();

    List<DownloadReport> findByReportStatusOrderByCreatedAtAsc(String status);

    Optional<DownloadReport> findFirstByReportStatusOrderByCreatedAtAsc(String status);

    // DownloadReportRepository.java

    @Query("SELECT r FROM DownloadReport r WHERE r.reportStatus = 'Processing' AND r.lastUpdatedAt < :cutoff")
    List<DownloadReport> findStaleProcessingReports(@Param("cutoff") Date cutoff);
}

