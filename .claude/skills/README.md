# Skills

This is a library, not a checklist. Load only the skills the current task needs. Sources, pinned commits and review notes: `SOURCES.md`.

The web design skills (taste-skill, frontend-design, impeccable, ui-ux-pro-max) are here for design principles only. Their React/CSS/Tailwind/GSAP/browser instructions don't apply: translate the idea to Compose and Material 3. `imagegen-frontend-mobile` and `brandkit` only generate images.

When skills disagree, the official android/skills win: `adaptive`, `edge-to-edge`, `navigation-3`, `navigation-event`, `testing-setup`, `wear-compose-m3`, `android-profiler`, `android-cli`.

Overlaps:
- Compose: chrisbanes `compose-*` / `kotlin-*` for one focused decision (`using-chrisbanes-skills` routes), rcosteira79 `compose` as the broad reference. Pick one per topic, don't load both.
- Material/UX: `material-3` for theme, tokens and components, `android-ux` for the M3 audit, `mobile-android-design` for extra samples only (its navigation is Navigation 2, use `navigation-3`).
- Testing: `testing-setup` sets up the stack, `compose-ui-testing-patterns` and `android-testing` for writing tests.
- Look: `minimalist-ui` is the chosen direction, but Material 3 wins where they clash (Roboto, button shapes).
- The `android` CLI is blocked by Smart App Control on this machine: use cmdline-tools 22.0 `sdkmanager`, adb and Gradle.
