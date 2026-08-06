# TraceGuard — guides & explainers

Shareable explainers for the release / testing teams (also on claude.ai as live pages).

| Guide | Audience | What it explains |
|---|---|---|
| **[Release-Impact-Manager.pdf](Release-Impact-Manager.pdf)** | Release / delivery leads | Will a release affect production? The go/no-go risk model — new-version vs live (BAU) users — without routes or git. |
| **[Release-Impact-Technical.pdf](Release-Impact-Technical.pdf)** | Release team (technical) | How Release Impact concludes *BAU changed vs not changed* — version route resolution + per-commit diff of the BAU route against its own previous version. |
| **[Release-Test-Guide.pdf](Release-Test-Guide.pdf)** | Testing team | The verify-with-logs workflow, the end-to-end (front-end + back-end + service-version) checks, and how the coverage-first / pass-rate verdict is decided. |

The `.pptx` decks are the earlier executive overviews.

> The PDFs are rendered from the HTML explainers (headless Chrome). To regenerate after an
> edit, re-render the source HTML to `docs/*.pdf`.
