# 0004 — No certificate pinning (2026-09-27)

**Decision.** No pins for OpenAI or Hugging Face. Instead: system CAs only (no user-installed CAs in
release), Certificate Transparency on Android 16+, host allow-lists in the HTTP clients, and SHA-256
checks for the model.

**Why.** We own none of these endpoints. OpenAI publishes no pins and sits behind Cloudflare; Hugging
Face serves files from a rotating CDN. A pin that goes stale takes the feature down for every user until
an update ships. The model's integrity doesn't depend on TLS at all (hash check), and the tokens are
only accepted by OpenAI anyway.

**Revisit** if we ever run our own endpoint.
