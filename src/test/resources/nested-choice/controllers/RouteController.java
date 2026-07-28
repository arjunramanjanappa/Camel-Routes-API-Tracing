package com.acme.route.rest;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** getRouteStatus resolves to R9.14_getRouteStatus: an unconditional callMyInfo plus a choice whose
 *  branches (callFetch → nested choice, or callGet) reach 4 distinct backends in total. */
@RestController
@RequestMapping("/get/route")
public class RouteController {

    @PostMapping("/status")
    public Object getRouteStatus(Object body) {
        return null;
    }
}
