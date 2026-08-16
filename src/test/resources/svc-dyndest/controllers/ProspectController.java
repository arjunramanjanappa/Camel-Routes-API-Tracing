package com.acme.prospect.rest;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * prospectCreateV1 (entry) resolves to R9.14_prospectCreateV1. Two &lt;when&gt; branches each set a DEST_ROUTE
 * constant and dispatch dynamically (toD direct:${FINAL_ROUTE_NAME}) to R9.14_manualauthDetails /
 * R9.14_basicdetails, which both call the SAME backend /api/application/v3/update at svc 6.0.
 */
@RestController
@RequestMapping("/v1/prospect")
public class ProspectController {

    @PostMapping("/create")
    public Object prospectCreateV1(Object body) {
        return null;
    }
}
