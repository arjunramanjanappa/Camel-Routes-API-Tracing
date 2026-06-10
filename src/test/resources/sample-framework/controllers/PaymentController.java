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
}
