package com.acme.confirm.rest;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** confirm (entry) resolves to R9.14_confirm, which calls the backend ft/bfs/txn/confirm (no leading slash). */
@RestController
@RequestMapping("/services/sg/manage")
public class ConfirmController {

    @PostMapping("/confirm")
    public Object confirm(Object body) {
        return null;
    }
}
