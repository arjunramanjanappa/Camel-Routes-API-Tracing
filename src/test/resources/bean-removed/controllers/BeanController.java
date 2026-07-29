package com.acme.bean.rest;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** beanApi: R9.14 (vs R9.4) drops a bean step the BAU route invoked — a backward-incompatible removal. */
@RestController
@RequestMapping("/svc")
public class BeanController {

    @PostMapping("/bean")
    public Object beanApi(Object body) {
        return null;
    }
}
