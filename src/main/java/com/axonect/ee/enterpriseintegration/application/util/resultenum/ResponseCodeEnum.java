package com.axonect.ee.enterpriseintegration.application.util.resultenum;

public enum ResponseCodeEnum {
    SUCCESS("2000","Operation Successful"),
    CREATE_SUCCESS("2001","Report Download in progress. Please Check “Reports” Section"),
    BAD_REQUEST("4000","bad request"),
    LOG_NOT_CONNECTED("5000","operational logs not connected"),
    BAD_REQUEST_INVALID_FIELDS("4000", "Missing or invalid required fields"),
    PARTIAL_SUCCESS("2007", "Partial Success"),
    OPERATION_FAILED("5000", "Operation Failed!"),
    NOT_FOUND("4000","Not found"),
    INTERNAL_SERVER_ERROR("5000","Internal Server Error"),
    REPORT_NOT_FOUND("4004", "Report not found"),
    REPORT_FILE_NOT_FOUND("4005", "Report file not found"),
    REPORT_DIR_ERROR("5001", "Error accessing report directory"),
    REPORT_READ_ERROR("5002", "Error reading report file"),
    SEQUENCE_GENERATE_ERROR("5003","Error generating download report sequence id");


    private String code;
    private String message;
    ResponseCodeEnum(String code, String message) {
        this.code = code;
        this.message = message;
    }
    public String code() {
        return code;
    }
    public String message() { return message; }
}

