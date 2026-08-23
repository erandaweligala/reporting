package com.axonect.ee.enterpriseintegration.application.constant;

public class LoggingAdviceConstants {

    private LoggingAdviceConstants(){}

    public static final String REQUEST_INITIATED = "RMS_REQUEST_INITIATED|0|REQUEST_METHOD:{}|REQUEST_URI:{}";
    public static final String REQUEST_TERMINATED = "RMS_REQUEST_TERMINATED|{}|REASON:{}";
    public static final String EXCEPTION_STACK_TRACE = "RMS_EXCEPTION|{}|ERROR_MESSAGE:{}|STACK_TRACE:{}";
    public static final String RMS_QUEUE = "RMS_QUEUE|{}|TYPE:{}|MESSAGE:{}";
    public static final String SERVICE_TERMINATION = "RMS_SERVICE_TERMINATED|{}|MESSAGE:{}";

    public static final String REPORT_GENERATION_STARTED = "Starting report generation process...";
    public static final String REPORT_SAVED_TO_DB = "Report entry saved in database. ID: {}";
    public static final String REPORT_SENT_TO_ASYNC = "Report ID {} sent to Async processing.";
    public static final String NO_MORE_DATA = "No more data found after processing {} records";
}

