package com.acme.status.rest;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * apiA (entry) resolves to R9.14_apiA. It always calls the 9.14 change backend /precapture (svc 2.3), then a
 * {@code <choice>} whose {@code <otherwise>} resolves DOWN to the BAU route R8.16_utAccount, whose backend
 * /utAccount sets NO serviceVersionNumber. Mirrors the user's wealth/orderplacement case.
 */
@RestController
@RequestMapping("/services/sg")
public class StatusController {

    @PostMapping("/status")
    public Object apiA(Object body) {
        return null;
    }
}
