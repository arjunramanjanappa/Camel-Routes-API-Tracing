package com.acme.bump.rest;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Minimal controller for the pure backend service-version bump risk test. */
@RestController
@RequestMapping("/svc")
public class BumpController {

    @CommandHandler(command = "BumpCommand")
    @PostMapping("/bump")
    public Object bumpApi(Object body) {
        return null;
    }
}
