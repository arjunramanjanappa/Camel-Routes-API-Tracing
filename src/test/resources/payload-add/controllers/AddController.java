package com.acme.add.rest;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** addApi: R9.14 (built from R9.8) only ADDS request fields — a backward-compatible, new-version-scoped change. */
@RestController
@RequestMapping("/svc")
public class AddController {

    @CommandHandler(command = "AddCommand")
    @PostMapping("/add")
    public Object addApi(Object body) {
        return null;
    }
}
