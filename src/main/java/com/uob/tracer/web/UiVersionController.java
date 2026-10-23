package com.uob.tracer.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which UI bundle this server is serving — read from the shipped {@code index.html} ({@code assets/index-<hash>.js}).
 * The running page compares it with its own script URL and offers a reload when they differ, so a user is told the
 * moment a newer build is live instead of working on a stale bundle.
 */
@RestController
public class UiVersionController {

    private static final Pattern BUNDLE = Pattern.compile("assets/(index-[A-Za-z0-9_-]+\\.js)");

    /** {@code {"bundle": "index-BnbRuSGs.js"}} — or an empty bundle when the UI isn't packaged with this server. */
    @GetMapping("/internal/ui-version")
    public Map<String, String> uiVersion() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("bundle", servedBundle());
        return out;
    }

    static String servedBundle() {
        ClassPathResource index = new ClassPathResource("static/index.html");
        if (!index.exists()) {
            return "";
        }
        try (InputStream in = index.getInputStream()) {
            Matcher m = BUNDLE.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return m.find() ? m.group(1) : "";
        } catch (Exception e) {
            return "";
        }
    }
}
