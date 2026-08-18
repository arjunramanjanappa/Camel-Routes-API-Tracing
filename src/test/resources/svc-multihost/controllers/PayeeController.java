package com.acme.payee.rest;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * getPayeeList (entry) → R9.14_getPayeeList. Its otherwise branch calls two routes that set the SAME api
 * (/bfs/payee/list) but DIFFERENT hostUrls: bpGetPayeelist → /bfs/bp/payee/list (svc 2.1) and
 * R9.14_fetGetPayeeList → /bfs/ft/payee/list (svc 3.2). The log line carries the hostUrl.
 */
@RestController
@RequestMapping("/services/sg/payee")
public class PayeeController {

    @PostMapping("/list")
    public Object getPayeeList(Object body) {
        return null;
    }
}
