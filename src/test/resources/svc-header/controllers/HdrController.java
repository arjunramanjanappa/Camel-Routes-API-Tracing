package com.acme.hdr.rest;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** apiH resolves to R9.14_apiH, which sets serviceVersionNumber=2.8 via setHeader, then calls a
 *  header-driven Velocity template whose #else fallback is 2.4 — the effective version must be 2.8. */
@RestController
@RequestMapping("/services/sg")
public class HdrController {

    @PostMapping("/hdr")
    public Object apiH(Object body) {
        return null;
    }
}
