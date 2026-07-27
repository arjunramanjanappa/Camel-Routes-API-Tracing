package com.acme.twin.rest;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * apiT (entry) resolves to R9.14_apiT, which branches (OR) to two DISTINCT release routes that both call
 * the SAME backend /shared at the SAME service version 2.0 — scenario 6. Both flows must be covered.
 */
@RestController
@RequestMapping("/services/sg")
public class TwinController {

    @PostMapping("/twin")
    public Object apiT(Object body) {
        return null;
    }
}
