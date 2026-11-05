# Desktop client (HMCL-oriented). Deferred after Android v0.1.

Windows / macOS / Linux are not in this Android launch slice. Tracked by issue #5. Download, account, and launch-command types live in `core/` so a later desktop shell can call the same code. The desktop app does not turn on the Android native-library check.

## Branding

Use `shared/Branding` — show `PRODUCT_NAME` everywhere; `PRODUCT_FULL_NAME` only as a hidden easter egg; formal About stays `PRODUCT_NAME`.
