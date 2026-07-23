package com.uob.tracer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.URI;
import java.util.Locale;

/**
 * Opens TraceGuard in the default browser once the server is ready — but only when
 * {@code tracer.open-browser=true}. The standalone launchers (TraceGuard.bat / .command) and the
 * jpackage {@code .exe} set that flag, so the desktop app pops the browser by itself; it stays
 * <b>off by default</b> so {@code spring-boot:run} / IntelliJ dev runs and headless server deployments
 * don't. Opening happens inside the JVM (java.awt.Desktop, else an OS command — rundll32 / open /
 * xdg-open), so it works where group policy blocks PowerShell.
 */
@Component
public class StartupBrowser {

    private static final Logger LOG = LoggerFactory.getLogger(StartupBrowser.class);

    private final boolean enabled;
    private final int port;

    public StartupBrowser(@Value("${tracer.open-browser:false}") boolean enabled,
                          @Value("${server.port:8080}") int port) {
        this.enabled = enabled;
        this.port = port;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void openOnReady() {
        if (!enabled) {
            return;
        }
        String url = "http://localhost:" + port + "/";
        if (openWithDesktop(url) || openWithOs(url)) {
            LOG.info("Opened {} in your default browser.", url);
        } else {
            LOG.info("TraceGuard is ready — open it in your browser: {}", url);
        }
    }

    private boolean openWithDesktop(String url) {
        try {
            if (!GraphicsEnvironment.isHeadless()
                    && Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return true;
            }
        } catch (Exception e) {
            LOG.debug("Desktop.browse failed ({}); falling back to an OS command.", e.toString());
        }
        return false;
    }

    private boolean openWithOs(String url) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url);
            } else if (os.contains("mac")) {
                pb = new ProcessBuilder("open", url);
            } else {
                pb = new ProcessBuilder("xdg-open", url);
            }
            pb.start();
            return true;
        } catch (Exception e) {
            LOG.debug("OS open command failed: {}", e.toString());
            return false;
        }
    }
}
