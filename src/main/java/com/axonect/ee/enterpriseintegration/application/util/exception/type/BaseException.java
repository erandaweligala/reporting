/**
 * Copyrights 2020 Axiata Digital Labs Pvt Ltd.
 * All Rights Reserved.
 *
 * These material are unpublished, proprietary, confidential source
 * code of Axiata Digital Labs Pvt Ltd (ADL) and constitute a TRADE
 * SECRET of ADL.
 *
 * ADL retains all title to and intellectual property rights in these
 * materials.
 *
 */
package com.axonect.ee.enterpriseintegration.application.util.exception.type;

import lombok.Getter;

@Getter
public class BaseException extends Exception {
    private String responseCode;

    public BaseException(String responseDescription) {
        super(responseDescription);
    }

    public BaseException(String responseCode, String responseDescription) {
        super(responseDescription);
        this.responseCode = responseCode;
    }
}

