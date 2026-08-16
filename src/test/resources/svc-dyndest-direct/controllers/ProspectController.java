package com.acme.prospect.rest;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * prospectCreateV1 → R9.14_prospectCreateV1. Two DEST_ROUTE branches resolve to R9.14_manualauthDetails /
 * R9.14_basicdetails, each of which sets the api /api/application/v3/update (svc 6.0) DIRECTLY — no shared
 * intermediate route. So each branch is its own owning route (branchRoute == flowRoute).
 */
@RestController
@RequestMapping("/v1/prospect")
public class ProspectController {

    @PostMapping("/create")
    public Object prospectCreateV1(Object body) {
        return null;
    }
}
