package com.acme.payment.rest;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sample controller mirroring the enterprise framework's pattern. The tracer
 * parses this source to map API path -> operation (the method name) and to read
 * the UFW {@code @CommandHandler} command. This file is sample input only; it is
 * NOT compiled as part of the tracer application.
 */
@RestController
@RequestMapping("/payment")
public class PaymentController {

    @CommandHandler(command = "FundTransferSubmitApiCommand")
    @PostMapping("/v1/fund/submit")
    public ApiSingleResponse fundTransferSubmitApi(Object body) {
        return null;
    }

    @CommandHandler(command = "FundTransferSubmitV2ApiCommand")
    @PostMapping("/v2/fund/submit")
    public ApiSingleResponse fundTransferSubmitV2Api(Object body) {
        return null;
    }

    // Scenario-5 shape: the request template comes AFTER the choice branches set the api.
    @CommandHandler(command = "LimitInitiateCommand")
    @PostMapping("/v2/limit/initiate")
    public ApiSingleResponse limitInitiateApi(Object body) {
        return null;
    }

    // Combination: template before the choice (inherited by one branch) + a branch
    // overriding with its own template — different backends, mixed .vm/.ftl.
    @CommandHandler(command = "ComboCommand")
    @PostMapping("/v2/combo")
    public ApiSingleResponse comboApi(Object body) {
        return null;
    }
}
