# Skill sources

Installed 2026-09-27. Every skill folder is an unmodified copy of the upstream folder at the commit below.
All 205 files were checked byte for byte against the upstream git blobs (`git hash-object`). The folder name
is the `name:` from the SKILL.md; only the two taste-skill folders differ from their upstream folder name.

Review, per skill: SKILL.md read in full, every file listed, all files searched for scripts, hooks and
settings files, network calls, `curl | sh`, eval/exec, base64 blobs, env/credential access, package installs,
prompt-injection phrases and hidden Unicode. The one executable file (Perfetto `trace_processor`) was read
in full. No installed skill has hooks, auto-run behaviour, obfuscated code, or instructions to disable
safety checks, run things silently or exfiltrate data. Nothing was rejected.

Project baseline for the prerequisite checks: AGP 9.4.1, Kotlin 2.4.20, Compose BOM 2026.09.00 (resolves to
Compose ui/foundation/runtime 1.12.1, material3 1.4.0, material3-adaptive-navigation-suite 1.4.0),
compileSdk 37, targetSdk 36, minSdk 31, Gradle 9.7.1. No Navigation library (screens switched by hand),
no DI framework, no Compose UI or screenshot tests yet. MainActivity already calls `enableEdgeToEdge()`
and the manifest sets `adjustResize`.

License texts are in `LICENSES/` (one per upstream repo, taken from the same commit as the skills),
since upstream keeps them at the repo root rather than in the skill folders.

## Repositories

| Repo | Commit | Commit date | License |
|---|---|---|---|
| [wshobson/agents](https://github.com/wshobson/agents) | `9b15b34b0bfc13a815cbfc2366e14ea549e09422` | 2026-09-26 | MIT |
| [hamen/material-3-skill](https://github.com/hamen/material-3-skill) | `14385f2bf3804d8779f8b4db2604211f1e70b4c1` | 2026-07-16 | MIT |
| [chrisbanes/skills](https://github.com/chrisbanes/skills) | `359126d6c13b9fe9f04cd190538813c2dfeda107` | 2026-09-26 | Apache-2.0 |
| [android/skills](https://github.com/android/skills) | `42dc2270e96032bd860bb94511e440aa00a43125` | 2026-09-25 | Apache-2.0 |
| [rcosteira79/android-skills](https://github.com/rcosteira79/android-skills) | `0f9af5ae88e7041cc3f901975237aedb1bd0edf6` | 2026-09-19 | MIT |
| [Leonxlnx/taste-skill](https://github.com/Leonxlnx/taste-skill) | `ce26fc25c0e5e8cab638f883de62d9a86ee5e45b` | 2026-09-26 | MIT |

## Installed skills

### wshobson/agents @ `9b15b34b0bfc13a815cbfc2366e14ea549e09422` (MIT), installed 2026-09-27

| Skill | Path in repo | Files | Review notes |
|---|---|---|---|
| mobile-android-design | `plugins/ui-design/skills/mobile-android-design` | 5 | Markdown only, no scripts, no network. Main content is in `references/details.md`. Navigation samples are Navigation 2 (`navigation-compose:2.7.7`, `kotlinx-serialization-json:1.6.3`), older than this project and than `navigation-3`; use `navigation-3` for navigation. Only the skill folder was taken, not the plugin's agents/commands. |

### hamen/material-3-skill @ `14385f2bf3804d8779f8b4db2604211f1e70b4c1` (MIT), installed 2026-09-27

| Skill | Path in repo | Files | Review notes |
|---|---|---|---|
| material-3 | `skills/material-3` | 8 | Markdown plus `.claude-plugin/plugin.json` (metadata only: name, version 1.1.1, author, license, keywords; no hooks, commands or MCP servers). `user-invokable`, `/material-3 audit` scores M3 compliance. Web sections say `npm install @material/web` / `@material/material-color-utilities`: web path only, ignore here. States that Roboto is the correct MD3 default and overrides frontend-design's "avoid Roboto". Expressive APIs: check against material3 1.4.0 from the BOM. |

### chrisbanes/skills @ `359126d6c13b9fe9f04cd190538813c2dfeda107` (Apache-2.0), installed 2026-09-27

All ten are Markdown only: no scripts, no network calls. Links between these skills resolve.

| Skill | Path in repo | Files | Review notes |
|---|---|---|---|
| using-chrisbanes-skills | `skills/using-chrisbanes-skills` | 1 | Router for the set. Also routes to gradle-run, android-benchmark-comparison, release-kotlin-library, to-plan and shepherd, which were not requested and are not installed (5 dead links). |
| compose-component-design | `skills/compose-component-design` | 3 | Slot and modifier API design. |
| compose-animations | `skills/compose-animations` | 2 | Animation API choice. Mentions Navigation Compose transitions; with Nav3 that is `NavDisplay`'s transition specs. |
| compose-state-and-effects | `skills/compose-state-and-effects` | 4 | State ownership, effects. Says not to add Preview tooling or touch build files unless authorised. |
| compose-focus-navigation | `skills/compose-focus-navigation` | 1 | Focus, keys, D-pad. |
| compose-performance | `skills/compose-performance` | 5 | Stability, deferred reads, measure-first. |
| compose-ui-testing-patterns | `skills/compose-ui-testing-patterns` | 2 | Test shapes. The project has no Compose UI test deps yet (BOM provides `ui-test-junit4` 1.12.1). |
| kotlin-concurrency-and-flow | `skills/kotlin-concurrency-and-flow` | 3 | Scopes, Flow state vs events. |
| kotlin-api-design | `skills/kotlin-api-design` | 4 | Function ownership, value classes, KMP boundaries. |
| kotlin-control-flow | `skills/kotlin-control-flow` | 1 | Uses `when` guard conditions (Kotlin 2.2+); fine on 2.4.20. |

### android/skills @ `42dc2270e96032bd860bb94511e440aa00a43125` (Apache-2.0, `LICENSE.txt` at repo root), installed 2026-09-27

Official Google skills. The date in brackets is the skill's own `last-updated`.

| Skill | Path in repo | Files | Review notes |
|---|---|---|---|
| adaptive | `jetpack-compose/adaptive` | 12 | (2026-09-22) Markdown only. Requires Navigation 3 for multi-pane (SceneStrategy, not ListDetailPaneScaffold). Asks the user before using experimental Grid. Final step runs screenshot tests but leaves the reference images for the user to update. Prerequisites below. |
| edge-to-edge | `system/edge-to-edge` | 1 | (2026-08-24) Markdown only. Edits Activities and the manifest, ends with `./gradlew build`. Prerequisites met. |
| navigation-3 | `navigation/navigation-3` | 28 | (2026-09-24) Markdown only (guide, Nav2 migration guide, 24 recipes). One upstream link in `recipes/deeplinks-syntheticbackstack.md` (`README.md`) points nowhere. Prerequisites below. |
| navigation-event | `navigation/navigation-event` | 5 | (2026-09-01) Markdown only. Says to use Nav3's own back handling instead of low-level dispatchers once Nav3 is in. |
| android-cli | `devtools/android-cli` | 3 | (2026-09-12) Markdown only, but see "Scripts, downloads and network" below: remote installer instructions and commands that change the environment. **The `android` CLI (cmdline-tools 23) is blocked by Smart App Control on this machine** (its bundled JRE's `nio.dll`), so none of its commands work here; use cmdline-tools 22.0 `sdkmanager`, `adb`, the emulator and Gradle. |
| testing-setup | `testing/testing-setup` | 5 | (2026-09-23) Markdown only. Would install Hilt when no DI framework exists, plus whatever is missing of Jacoco, Robolectric, Compose screenshot testing and Dropshots (Mockk only if needed): dependency decisions, confirm first. Asks before writing `AGENTS.md` / `docs/testing.md`. Prerequisites below. |
| android-profiler | `profilers/android-profiler` | 28 | (2026-08-06) Contains the one executable file of the whole library, `references/perfetto/bin/trace_processor`, and download instructions; see below. Needs Python 3 (system Python 3.14 works), curl, adb and a device with USB debugging. |
| wear-compose-m3 | `wear/wear-compose-m3` | 53 | (2026-09-24) Markdown only (guidance, M3 migration guide, 51 sample files). Reads the latest stable `androidx.wear.compose:compose-material3` from Google's Maven metadata (network read). Wants Navigation3 for Wear (`compose-navigation3`, `SwipeDismissableSceneStrategy`). Prerequisites met; the Wear module doesn't exist yet. |

### rcosteira79/android-skills @ `0f9af5ae88e7041cc3f901975237aedb1bd0edf6` (MIT), installed 2026-09-27

All six are Markdown only.

| Skill | Path in repo | Files | Review notes |
|---|---|---|---|
| compose | `plugins/android-skills/skills/compose` | 22 | Broad Compose + Compose Multiplatform reference (ignore the Desktop/iOS/Web parts). Tells the agent to check live source: raw.githubusercontent.com (androidx-main, jb-main), android.googlesource.com, `gh api repos/androidx/androidx/contents/...` (read-only), or an `android-sources` MCP server (not installed). Uses `android docs` (blocked here). "`android-skills:android-ux`" in the text is `android-ux` here. The generic name `compose` collides with nothing in the project, `~/.claude/skills` or the installed plugins. |
| android-ux | `plugins/android-skills/skills/android-ux` | 1 | M3 audit and platform facts. Its quick-grep commands use `rg --type kt`, which ripgrep rejects ("unrecognized file type: kt"); use `rg -t kotlin`. The in-app contrast sample uses MDC-Android `Hct`/`SchemeContent` (`@RestrictTo`, the skill says so). |
| coil-compose | `plugins/android-skills/skills/coil-compose` | 1 | Coil 3 guidance. Coil is not a project dependency yet. |
| android-testing | `plugins/android-skills/skills/android-testing` | 1 | Test-first rules and Compose test traps. Points to `testing-setup` for bootstrapping (installed here, no need for `android skills add`). |
| android-debugging | `plugins/android-skills/skills/android-debugging` | 1 | adb/logcat/Gradle/retrace commands, run only while debugging. Suggests `android layout` (blocked here) and optional LeakCanary/jadx. |
| android-source-search | `plugins/android-skills/skills/android-source-search` | 1 | Read-only source lookups (Gitiles `?format=TEXT`, raw GitHub, `gh api`). |

### Leonxlnx/taste-skill @ `ce26fc25c0e5e8cab638f883de62d9a86ee5e45b` (MIT), installed 2026-09-27

Web design skills: principles only, the CSS/Tailwind/React parts have to be translated to Compose.

| Skill | Path in repo | Files | Review notes |
|---|---|---|---|
| redesign-existing-projects | `skills/redesign-skill` (folder renamed to its skill name) | 1 | Audit checklist for existing UIs (type, colour, layout, states, copy). Uses picsum.photos placeholder URLs. |
| minimalist-ui | `skills/minimalist-skill` (folder renamed to its skill name) | 1 | The chosen visual direction. Bans Inter/Roboto/Open Sans and pill-shaped primary buttons, which clashes with Material 3 defaults; Material 3 wins there. |
| imagegen-frontend-mobile | `skills/imagegen-frontend-mobile` | 1 | Image generation only, no code. Defaults to phone mockups and `IMAGE_GENERATION_EAGERNESS: 10` ("do not be lazy with screen count"): every image call can cost credits, so give it a screen count. No round-watch mode; ask for Wear frames explicitly. |
| brandkit | `skills/brandkit` | 1 | Image generation only (brand/identity boards). |

Not installed from this repo: `high-end-visual-design` (`skills/soft-skill`) on purpose; `design-taste-frontend` and
`image-to-code` are already installed at user level (see below).

## Scripts, downloads and network

- **android-profiler, `references/perfetto/bin/trace_processor`** (only executable file). Perfetto's official
  auto-generated Python wrapper (Apache-2.0, "DO NOT EDIT"). Runs only when invoked
  (`python "<skill>/references/perfetto/bin/trace_processor" ...` on Windows). First run downloads the pinned
  `trace_processor_shell` v57.2 native binary (Windows: `trace_processor_shell.exe`, 13.6 MB) from
  `commondatastorage.googleapis.com/perfetto-luci-artifacts` with curl, checks its SHA-256 against the pinned
  value, caches it in `~/.local/share/perfetto/prebuilts/` and runs it. It can also download a trace when given
  an http(s) URL or a Perfetto UI share link. Benign. Not tested here; the downloaded `.exe` may hit Smart App
  Control like the `android` CLI did.
- **android-profiler, recording workflow** (`recording/workflows/perfetto-trace-recording/perfetto_trace_recording.md`)
  tells the agent to `curl -O` four helper scripts (`java_heap_dump`, `heap_profile`, `cpu_profile`,
  `record_android_trace`) from `raw.githubusercontent.com/google/perfetto/main/tools`, `chmod +x` and run them.
  Unpinned (`main`), fetched at run time, not part of this review. Only when a recording is asked for; say yes
  explicitly before it downloads. `record_android_trace` can open the result in ui.perfetto.dev.
- **android-cli, "Installation"**: if `android` is not on PATH it says to run Google's remote installer
  (`curl -fsSL https://dl.google.com/android/cli/latest/<platform>/install.sh | bash`; on Windows download
  `install.cmd` to `%TEMP%` and run it). Also documents `android init` (sets up config and default skills),
  `android skills add/remove/update`, `android update [--url]`, `android completion` (writes to the shell
  profile) and `android sdk install/update/remove`. None of this may run without asking. Moot on this
  machine while Smart App Control blocks the CLI.
- **wear-compose-m3**: fetches `https://dl.google.com/dl/android/maven2/androidx/wear/compose/compose-material3/maven-metadata.xml`.
- **compose, android-source-search**: read-only fetches of public source (raw.githubusercontent.com,
  android.googlesource.com, `gh api` with the logged-in gh).
- **material-3, redesign-existing-projects, minimalist-ui**: npm installs, Google Fonts links and picsum.photos
  URLs inside web samples only.
- Several skills mention `android skills add <name>` (navigation-3 and testing-setup references, compose,
  android-testing, android-debugging). Not needed, the skills are here.

## Prerequisites vs this project (android/skills)

| Skill | Asks for | Project | Result |
|---|---|---|---|
| adaptive | All screens in Compose | Compose | ok |
| adaptive | Jetpack Navigation 3 | none | **missing**: adopt `navigation-3` first |
| adaptive | Grid/FlexBox/MediaQuery, experimental "from Compose 1.11.0-beta01" | foundation 1.12.1 via BOM | ok (needs `@OptIn`, ask first) |
| adaptive | Its reference setup pages pin `foundation-layout` `1.13.0-alpha03` | BOM 1.12.1 | **mismatch**: don't add the alpha pin over the BOM |
| adaptive | `androidx.compose.material3.adaptive:adaptive-navigation3` | not in BOM, not a dependency | needs its own version |
| adaptive | `NavigationSuiteScaffold` | material3-adaptive-navigation-suite 1.4.0 in BOM | ok (not declared yet) |
| adaptive | Screenshot tests before changes | none | missing |
| edge-to-edge | Compose, targetSdk >= 35 | targetSdk 36, `enableEdgeToEdge()`, `adjustResize` | ok |
| navigation-3 | compileSdk >= 36, minSdk >= 23 | 37 / 31 | ok |
| navigation-3 | `navigation3` 1.2.0, `lifecycle-viewmodel-navigation3` 2.11.0 | lifecycle 2.11.0 | ok (not declared yet) |
| navigation-3 | KotlinX Serialization plugin for `@Serializable` keys | plugin in catalog | ok |
| navigation-3 | Migration guide assumes Navigation 2 with type-safe routes | no Navigation library | use the guide/recipes as a fresh adoption, skip the Nav2 migration steps |
| navigation-event | compileSdk >= 36, `navigationevent` 1.0.0 | 37 | ok |
| navigation-event | `enableOnBackInvokedCallback="true"` for API 33-35 devices | not set, minSdk 31 | check when adding predictive back |
| testing-setup | AGP >= 9.5.0-alpha03 for screenshot tests as AGP test suites | AGP 9.4.1 | **mismatch**: skill falls back to the deprecated `com.android.compose.screenshot` plugin 0.0.1-alpha16 (AGP >= 9.0, Kotlin >= 2.2.10, JDK 17, `android.experimental.enableScreenshotTest=true`) and says not to upgrade AGP for it |
| testing-setup | A DI framework (installs Hilt if none) | none | decision needed |
| testing-setup | Respect the existing test stack | JUnit4, Truth, Turbine | ok |
| android-cli | Working `android` CLI | blocked by Smart App Control | **not usable**; use cmdline-tools 22.0 `sdkmanager` |
| android-profiler | Python 3, curl, adb, USB debugging | Python 3.14, curl, platform-tools | ok (first run downloads a binary) |
| wear-compose-m3 | Kotlin >= 2.0 with the Compose plugin | 2.4.20 | ok |
| wear-compose-m3 | minSdk >= 25 | 31 | ok |
| wear-compose-m3 | compose-material3 1.6.x: compileSdk >= 35, AGP >= 8.6.0; 1.7.x: compileSdk >= 37, AGP >= 9.1.0 | 37 / 9.4.1 | ok |

## Already installed, not duplicated

| Skill | Installed as | Upstream | Commit | Path in repo | License | Check |
|---|---|---|---|---|---|---|
| ui-ux-pro-max | `~/.claude/skills/ui-ux-pro-max` | [nextlevelbuilder/ui-ux-pro-max-skill](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill) | `09170eec67eefd46a7ae85de61b40c194020f997` (2026-09-27) | `.claude/skills/ui-ux-pro-max` | MIT | SKILL.md identical; 6 data/script/test files differ from upstream HEAD (`data/stacks/threejs.csv`, `scripts/design_system.py`, 4 test files). Installed 2026-09-26, HEAD commit is from 2026-09-27. |
| design-taste-frontend | `~/.claude/skills/design-taste-frontend` | [Leonxlnx/taste-skill](https://github.com/Leonxlnx/taste-skill) | `ce26fc25c0e5e8cab638f883de62d9a86ee5e45b` (2026-09-26) | `skills/taste-skill` | MIT | identical to upstream |
| image-to-code | `~/.claude/skills/image-to-code` | [Leonxlnx/taste-skill](https://github.com/Leonxlnx/taste-skill) | `ce26fc25c0e5e8cab638f883de62d9a86ee5e45b` (2026-09-26) | `skills/image-to-code-skill` | MIT | identical to upstream |
| frontend-design | plugin `frontend-design@claude-plugins-official` (anthropics/claude-plugins-official @ `fa59bc9037741ecfa131aa27938272605710d7b2`) | [anthropics/skills](https://github.com/anthropics/skills) | `33375500bcea98d610eb30ce10ac4e59b89c390d` (2026-09-24) | `skills/frontend-design` | Apache-2.0 | identical to upstream (line endings only) |
| impeccable | plugin `impeccable@impeccable` 4.4.0 | [pbakaus/impeccable](https://github.com/pbakaus/impeccable) | `9d715cc4f5564a990ca8345abfdd5df6dc9b41c8` (2026-09-24) | `plugin/skills/impeccable` (mirrored in `.claude/skills/impeccable` and other agent folders) | Apache-2.0 | installed from this same commit, identical. Note: the plugin ships `plugin/hooks/hooks.json` with SessionStart, PostToolUse (Edit\|Write) and Stop hooks that run `skills/impeccable/scripts/impeccable hook`. They are active through the user-level plugin and also fire in this project. Nothing here installs or changes them; not reviewed further. |

**ui-ux-pro-max has a Jetpack Compose stack.** Run the stack search explicitly, it is not auto-detected
(Step 1 only looks at package.json, pubspec.yaml, Xcode, composer.json and React Native markers):

```
python "$HOME/.claude/skills/ui-ux-pro-max/scripts/search.py" "<keywords>" --stack jetpack-compose
```

`-s jetpack-compose` is the short form. `--stack` is ignored together with `--design-system`, so run it as a
separate query. The SKILL.md writes the path as `${CLAUDE_PLUGIN_ROOT}/.claude/skills/ui-ux-pro-max/...`,
which is unset for a user-level skill; use the path above. Tested 2026-09-27 with system Python 3.14. Its
Compose rows say "jetpack-compose 1.11.4 (current stable)", verified 2026-08-13; the project is on 1.12.1.
