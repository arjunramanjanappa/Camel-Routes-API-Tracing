package com.arjun.tracer.web;

import com.arjun.tracer.api.TraceRequest;
import com.arjun.tracer.api.TraceResponse;
import com.arjun.tracer.service.RouteTraceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Exposes the route trace. Supports both a GET (for the UI / quick links) and a
 * POST (JSON body) form.
 */
@RestController
public class RouteGraphController {

    private final RouteTraceService service;

    public RouteGraphController(RouteTraceService service) {
        this.service = service;
    }

    @GetMapping("/internal/route-graph")
    public TraceResponse traceGet(
            @RequestParam String api,
            @RequestParam(required = false) String version,
            @RequestParam(required = false) String transferType,
            @RequestParam(required = false) String sourceDir) {
        return service.trace(new TraceRequest(api, version, transferType, sourceDir));
    }

    @PostMapping("/internal/route-graph")
    public TraceResponse tracePost(@RequestBody TraceRequest request) {
        return service.trace(request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", e.getMessage()));
    }
}
