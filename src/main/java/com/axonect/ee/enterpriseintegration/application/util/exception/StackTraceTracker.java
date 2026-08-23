package com.axonect.ee.enterpriseintegration.application.util.exception;

import org.springframework.stereotype.Component;

@Component
public class StackTraceTracker {
    private StackTraceTracker() {
        // private constructor to hide the implicit public one
    }

    public static String displayStackStraceArray(StackTraceElement[] stackTraceElements) {
        StringBuilder stringBuilder = new StringBuilder();
        if (stackTraceElements != null) {
            for (StackTraceElement elem : stackTraceElements) {
                if (elem.getClassName().startsWith("com.adl.et.telco.dte.template.baseapp") && elem.getLineNumber() > 0) {
                    stringBuilder.append(elem);
                    break;
                }
            }
        }
        return stringBuilder.toString();
    }
}
