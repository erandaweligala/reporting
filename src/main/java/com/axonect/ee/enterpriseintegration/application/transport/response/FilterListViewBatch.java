package com.axonect.ee.enterpriseintegration.application.transport.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class FilterListViewBatch {
    private List<Map<String, Object>> tableData; // rows
    private int page;
    private int pageSize;
    private int totalRecords;
}
