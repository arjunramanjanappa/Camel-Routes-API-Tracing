package com.acme.status.rest;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** apiA (entry) resolves to R9.14_apiA, whose flow calls R9.14_apiB (4.0) and the BAU R8.8_apiC (2.5). */
@RestController
@RequestMapping("/services/sg")
public class StatusController {

    @PostMapping("/status")
    public Object apiA(Object body) {
        return null;
    }
}
