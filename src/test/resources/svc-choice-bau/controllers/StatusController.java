package com.acme.status.rest;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * apiA (entry) resolves to R9.14_apiA. Its flow always calls the 9.14 backend /myInfo, then a
 * {@code <choice>}: the {@code <when>} calls the 9.14 backend /fetch, the {@code <otherwise>} resolves
 * DOWN to the BAU route R9.10_callGet (its own backend /getLegacy). Mirrors the user's example: a new
 * 9.14 route that internally reaches a resolved-down (BAU) route.
 */
@RestController
@RequestMapping("/services/sg")
public class StatusController {

    @PostMapping("/status")
    public Object apiA(Object body) {
        return null;
    }
}
