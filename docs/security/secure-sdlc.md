# How changes get in

1. Work on a branch, open a pull request into `main`.
2. CI must be green: format, fixture guard, unit tests, lint, debug + release build, SBOM, gitleaks,
   mobsfscan, OSV. Branch protection needs GitHub Pro on a private repo (planned).
3. Commit messages are plain and carry no co-author or generator lines (hook + CI check).
4. Releases are built by CI only, from a tag, with the upload key added at that point.

## Dependencies

- Versions live in `gradle/libs.versions.toml`; GitHub Actions are pinned by commit SHA.
- Dependabot opens grouped updates weekly with a 7-day cooldown (new releases wait a week).
- Before the first Play release: Gradle dependency verification (`gradle/verification-metadata.xml`,
  SHA-256, Linux + Windows entries) — Dependabot can't update it, so refresh it by hand with each bump.

## Vulnerabilities

| Severity | Fix within |
|---|---|
| Critical | 7 days |
| High | 30 days |
| Medium | next release |
| Low | backlog |

Anything that can't be fixed in time goes into `exceptions.md` with a reason and an end date.

## Release checklist

- [ ] CI green on the tag, SBOM attached
- [ ] Release build does a real on-device inference and a ChatGPT turn (R8 can break reflection/JNI)
- [ ] `adb logcat` leak check clean (no tokens, codes or prompt text)
- [ ] Threat model and Data safety answers still true
- [ ] Test report for the milestone in `docs/test-reports/`
