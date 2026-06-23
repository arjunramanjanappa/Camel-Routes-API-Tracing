package com.acme.bump.rest;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Minimal controller for the release-diff service-version test. */
@RestController
@RequestMapping("/svc")
public class BumpController {

    @PostMapping("/bump")
    public Object bumpApi(Object body) {
        return null;
    }

    // Only an un-versioned (base) route exists for this one — no R<ver>_ variant.
    @PostMapping("/base-only")
    public Object baseOnlyApi(Object body) {
        return null;
    }
}
