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
}
