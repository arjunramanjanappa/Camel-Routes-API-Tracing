package com.acme.remove.rest;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** removeApi: R9.14 (built from R9.8) REMOVES request fields — intentional, new-version-scoped; the old app
 *  keeps calling the unchanged R9.8, so it needs no backward-compat test. */
@RestController
@RequestMapping("/svc")
public class RemoveController {

    @CommandHandler(command = "RemoveCommand")
    @PostMapping("/remove")
    public Object removeApi(Object body) {
        return null;
    }
}
