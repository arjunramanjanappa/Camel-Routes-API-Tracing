package com.uob.tracer;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * TLS trust configuration for outbound HTTPS (the Bitbucket clone/fetch over an access token).
 *
 * <p>Corporate Bitbucket servers usually present a certificate signed by an INTERNAL CA that the JVM's
 * bundled {@code cacerts} does not know — so JGit fails with "PKIX path building failed: unable to find valid
 * certification path". On Windows that internal CA is almost always already trusted by the OS (in the Windows
 * "Root"/"CA" certificate stores, pushed by group policy). So on startup we install a default SSLContext that
 * trusts the JVM {@code cacerts} <b>and</b> the Windows system certificate stores — the internal CA is then
 * trusted automatically, with no manual cert import and without weakening verification.
 *
 * <p>Last resort for a machine whose cert isn't in either store (e.g. a bare self-signed Bitbucket, or a
 * non-Windows host): set {@code tracer.git.insecure-tls=true} to disable verification entirely. That is
 * insecure — use only on a trusted network — and is logged loudly.
 */
@Component
public class TlsTrust {

    private static final Logger LOG = LoggerFactory.getLogger(TlsTrust.class);

    @Value("${tracer.git.insecure-tls:false}")
    private boolean insecure;

    @PostConstruct
    void configure() {
        try {
            if (insecure) {
                installTrustAll();
                LOG.warn("tracer.git.insecure-tls=true — TLS CERTIFICATE VERIFICATION IS DISABLED for outbound "
                        + "HTTPS (Bitbucket). Use only on a trusted network; prefer trusting your corporate CA instead.");
                return;
            }
            installSystemPlusCacerts();
        } catch (Exception e) {
            LOG.warn("Could not extend TLS trust ({}); falling back to the JVM default truststore. If Bitbucket "
                    + "checkout fails with a certificate error, import your corporate CA into cacerts or set "
                    + "tracer.git.insecure-tls=true.", e.toString());
        }
    }

    /** Trust the JVM {@code cacerts} AND, on Windows, the Windows system certificate stores (Root + CA). */
    private void installSystemPlusCacerts() throws Exception {
        List<X509TrustManager> managers = new ArrayList<>();
        managers.add(trustManagerFor(null));   // JVM default: cacerts (public CAs)

        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (windows) {
            for (String store : new String[]{"Windows-ROOT", "Windows-MY"}) {
                try {
                    KeyStore ks = KeyStore.getInstance(store);
                    ks.load(null, null);
                    managers.add(trustManagerFor(ks));   // corporate CAs pushed to the OS live here
                } catch (Exception e) {
                    LOG.debug("Windows keystore {} unavailable ({})", store, e.getMessage());
                }
            }
        }

        if (managers.size() <= 1) {
            return;   // nothing extra to trust — leave the JVM default in place
        }
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{new AnyOfTrustManager(managers)}, null);
        applyDefault(ctx);
        LOG.info("TLS trust extended: JVM cacerts + {} Windows system certificate store(s) (covers an internal "
                + "corporate CA for Bitbucket).", managers.size() - 1);
    }

    private void installTrustAll() throws Exception {
        X509TrustManager trustAll = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{trustAll}, new SecureRandom());
        applyDefault(ctx);
        HttpsURLConnection.setDefaultHostnameVerifier((host, session) -> true);
    }

    /** Make this context the process default so JGit's JDK HTTP connection uses it. */
    private static void applyDefault(SSLContext ctx) {
        SSLContext.setDefault(ctx);
        HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
    }

    private static X509TrustManager trustManagerFor(KeyStore ks) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);   // null → the JVM default cacerts
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager x) {
                return x;
            }
        }
        throw new IllegalStateException("No X509TrustManager from the default factory");
    }

    /** A trust manager that accepts a chain if ANY of the delegates trusts it (cacerts OR the Windows stores). */
    private record AnyOfTrustManager(List<X509TrustManager> delegates) implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            check(chain, authType, true);
        }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            check(chain, authType, false);
        }
        private void check(X509Certificate[] chain, String authType, boolean client) throws CertificateException {
            CertificateException last = null;
            for (X509TrustManager tm : delegates) {
                try {
                    if (client) {
                        tm.checkClientTrusted(chain, authType);
                    } else {
                        tm.checkServerTrusted(chain, authType);
                    }
                    return;   // trusted by at least one store
                } catch (CertificateException e) {
                    last = e;
                }
            }
            throw last != null ? last : new CertificateException("No trust manager accepted the certificate");
        }
        @Override public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> all = new ArrayList<>();
            for (X509TrustManager tm : delegates) {
                all.addAll(Arrays.asList(tm.getAcceptedIssuers()));
            }
            return all.toArray(new X509Certificate[0]);
        }
    }
}
