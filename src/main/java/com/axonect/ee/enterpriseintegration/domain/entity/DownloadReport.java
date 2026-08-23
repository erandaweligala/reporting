package com.axonect.ee.enterpriseintegration.domain.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


import java.util.Date;

@Entity
@Table(name = "REPORT_DOWNLOAD")
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DownloadReport {

    @Id
    @GeneratedValue(
            strategy = GenerationType.SEQUENCE,
            generator = "REPORT_DOWNLOAD_SEQ"
    )
    @SequenceGenerator(
            name = "REPORT_DOWNLOAD_SEQ",
            sequenceName = "REPORT_DOWNLOAD_SEQ",
            allocationSize = 1
    )
    @Column(name = "ID", nullable = false)
    private Long id;

    @Column(name = "CREATED_BY")
    private String createdBy;

    @Column(name = "CREATED_AT")
    @Builder.Default
    private Date createdAt = new Date();

    @Column(name = "REPORT_TYPE")
    private String reportType;

    @Column(name = "REPORT_STATUS")
    @Builder.Default
    private String reportStatus = "NOT_STARTED";

    @Lob
    @Column(name = "FILTER_VALUES")
    private String filterValuesJson;

    @Column(name = "LAST_UPDATED_AT")
    @Builder.Default
    private Date lastUpdatedAt = new Date();

    @Column(name = "UPDATED_BY")
    private String updatedBy;

    @Column(name = "UPDATED_AT")
    private Date updatedAt;

    @Column(name = "EXECUTED_USER")
    private String executedUser;

    @Column(name = "DESCRIPTION")
    private String description;

    @Column(name = "REPORT_NAME")
    private String reportName;

    @Column(name = "FORMAT")
    @Builder.Default
    private String format = "EXCEL";

    @Column(name = "CLASSIFICATION_LEVEL")
    private String classificationLevel;
}


