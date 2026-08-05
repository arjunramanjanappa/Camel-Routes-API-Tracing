package com.acme.broken.rest;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** brokenApi: its 9.14 request template has a missing comma (invalid JSON once rendered). */
@RestController
@RequestMapping("/svc")
public class BrokenController {
    @PostMapping("/broken")
    public Object brokenApi(Object body) { return null; }
}
