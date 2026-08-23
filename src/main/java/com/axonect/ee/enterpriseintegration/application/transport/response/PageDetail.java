package com.axonect.ee.enterpriseintegration.application.transport.response;

import lombok.Data;

@Data
public class PageDetail {
    private String totalRecords;
    private String pageNumber;
    private String pageElementCount;
}
