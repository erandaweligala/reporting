package com.axonect.ee.enterpriseintegration.application.transport.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ColumnMetaData {
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String label;
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String dataValue;
    @Builder.Default
    private Integer columnWidth=100;
    @Builder.Default
    private Integer index=0;
    @Builder.Default
    private String searchType="text";
    @Builder.Default
    private String dataType="text";
    @Builder.Default
    private Boolean enable=false;
    @Builder.Default
    private Boolean filtered=false;
    @Builder.Default
    private List<StatusValue> list=null;
}

