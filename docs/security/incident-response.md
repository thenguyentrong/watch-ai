# Incident response

Owner: Vinh (vyde.apps@gmail.com). Keep a dated note of every step in `docs/security/incidents/`.

**GDPR:** if personal data is affected, the data protection authority must be told within 72 hours of
becoming aware, and users without undue delay if the risk is high. With no servers we rarely hold
personal data, but a vulnerable app version can still expose users' tokens on their phones.

| Situation | Do this |
|---|---|
| Bug that could leak ChatGPT tokens | Stop the rollout in Play Console, fix, force update (in-app update priority ≥ 4), tell users to sign out and use ChatGPT → Settings → Security → log out of all devices. |
| OpenAI blocks or changes Codex sign-in | Ship an update that disables the route (`ChatGptSettings.enabled = false` shows "needs an app update"), keep on-device answers working, move to SIWC. |
| Malicious or vulnerable dependency | Pin to a safe version or remove, rebuild, check SBOMs of released versions to see who is affected. |
| Upload key leaked | Reset it through Play Console, rotate every other credential on the machine. |
| Model file tampered upstream | The SHA-256 check blocks it; if a pinned file itself is bad, ship an update with a different pinned file. |
| Report of a harmful answer pattern | Reproduce with synthetic prompts, adjust the system prompt, note it in the release notes. |

After every incident: what happened, why, what changed. Add the lesson to the threat model.
