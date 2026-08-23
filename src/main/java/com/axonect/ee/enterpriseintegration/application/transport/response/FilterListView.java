package com.axonect.ee.enterpriseintegration.application.transport.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class FilterListView {

    private List<ColumnMetaData> columnNames;
    private List<Map<String,Object>> tableData;
}

