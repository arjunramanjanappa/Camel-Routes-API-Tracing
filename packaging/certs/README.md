# Corporate CA cert(s) baked into the shipped bundle

Drop your internal **CA certificate** file(s) here and they are imported into the
bundled runtime's `cacerts` at build time (`mvn -Pexe package` and `mvn -Pdist package`).
Every user then trusts your internal Bitbucket over HTTPS **out of the box** — no
per-user cert import, no `tracer.git.insecure-tls`. Injected once, shared by all.

- Accepted extensions: `*.crt`, `*.pem`, `*.cer` (a DER or PEM X.509 cert).
- Use the **root CA** certificate (the CA that signs the Bitbucket server cert) —
  one root cert is normally enough. It goes in under alias `traceguard-corp-ca`.
- This folder is **empty by default**, so a normal build injects nothing (no-op).
- Where it lands: `runtime/lib/security/cacerts` in the `.exe` app-image, and
  `jre/lib/security/cacerts` in the `.zip` bundle.

## How to get the CA cert (Windows)

`certmgr.msc` → Trusted Root Certification Authorities → Certificates → find your
corporate root CA → right-click → All Tasks → Export → **Base-64 encoded X.509
(.CER)** → save it into this folder.

## Notes

- This is a **public** root CA certificate, not a secret — safe to commit if your
  team wants it version-controlled, or keep it local and drop it in before building.
- It complements the runtime Windows-cert-store trust (`TlsTrust`): the Windows
  store covers CAs already pushed to the OS; this bake-in covers machines where the
  CA isn't in the OS store. If you have **several** distinct corporate CAs, prefer
  the OS store (or add extra `keytool` steps) — the build imports one under the
  fixed alias above.
