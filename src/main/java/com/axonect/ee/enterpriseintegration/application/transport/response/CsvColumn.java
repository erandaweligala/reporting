package com.axonect.ee.enterpriseintegration.application.transport.response;

import lombok.Data;
import lombok.Getter;

@Data
@Getter
public class CsvColumn {
    private final String key;
    private final String label;

    public CsvColumn(String key, String label) {
        this.key = key;
        this.label = label;
    }

}
