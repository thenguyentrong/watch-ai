# Security

Report a vulnerability to **vyde.apps@gmail.com** with "security" in the subject. Please don't open a
public issue. You get an answer within 3 working days; fixes follow the SLAs in
`docs/security/secure-sdlc.md` (critical 7 days, high 30 days).

Supported: the latest release only.

What's in scope: the Android app, how it stores the ChatGPT sign-in, how it talks to OpenAI and
Hugging Face, and the on-device model handling. There is no server of ours.

How it's built: `docs/security/threat-model.md` and `docs/security/controls.md`.
