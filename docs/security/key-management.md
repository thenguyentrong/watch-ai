# Key management

| Key | Where it lives | Who can use it | Rotation / loss |
|---|---|---|---|
| Vault master key `watchai_vault_master_v1` | Android Keystore on each phone (StrongBox on the S23 Ultra), AES-256-GCM, non-exportable | The app only | Created on first use. If it's lost or invalidated the vault wipes and the user signs in again. |
| Tink keyset (encrypts the tokens) | `noBackupFilesDir/vault/keyset.bin`, encrypted by the master key | The app only | New keyset whenever the master key is new. |
| ChatGPT tokens | Encrypted in the vault | The app, for that user's own account | Refresh tokens rotate on every refresh; sign-out revokes and wipes. |
| App signing key | Play App Signing (Google) | Google | Managed by Google. |
| Upload key | Password manager + offline copy, never in the repo or CI until release builds move to CI | Vinh | Rotate through Play Console if lost or leaked. |
| Shared debug keystore (dev only) | CI secret + local copy | Dev builds | Replace anytime; never used for releases. |
| GitHub / Play Console accounts | Passkey / 2-step | Vinh | Review in the monthly control review. |

Not in the repo, ever: keystores, `.env` files, tokens. `.gitignore` blocks `*.jks`, `*.keystore`, `.env*`;
gitleaks runs in CI.
