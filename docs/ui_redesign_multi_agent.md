# PocketMind UI Redesign Multi-Agent Plan

## Visual Directions

Default implementation direction: Calm AI Console.

| Direction | Intent | Use when |
| --- | --- | --- |
| Calm AI Console | Cool white/dark surfaces, quiet AI accent colors, document-like assistant output, command-bar composer. | Default production path. |
| Soft Native Minimal | Closer to Android system Material styling, lower contrast accents, more native controls. | Brand should feel more OS-integrated than AI-product-like. |
| Dark Focus Agent | Dark-first task cockpit, stronger status contrast, denser action/trace surfaces. | Operator workflows and long agent runs matter more than casual chat. |

## Agent Boundaries

Lead Agent owns integration, test selection, and conflict resolution. It should not make broad visual changes outside the selected direction.

Design System Agent owns `ui/theme/Theme.kt` and shared primitives/tokens only. Page agents must use theme colors, semantic colors, typography, and shapes from this layer instead of adding local palettes.

Shell Agent owns top bar, empty state, message list, message bubbles, markdown/code display, and resource indicator styling.

Composer Agent owns attachment, voice, model, send/stop, shared-input, remote-attachment, and voice privacy composer states.

Sheets Agent owns model manager, sessions, background tasks, memory, remote configuration, trust/privacy, and confirmation sheets.

QA Agent owns Compose UI tests, large-font/landscape checks, accessibility labels, and screenshot evidence.

## Invariants

- Keep `PocketMindScreen` public parameters stable.
- Keep existing `testTag` values stable.
- Keep permission, privacy, remote-send, voice, and model-management behavior unchanged.
- Do not add a UI framework dependency unless a specific component cannot be built with Compose Material3.
- Prefer theme/token changes over per-component hardcoded colors.

## Verification

Minimum local checks after each visual slice:

```bash
./gradlew :app:testDebugUnitTest
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.bytedance.zgx.pocketmind.MainActivityAdaptiveUiTest
```

Screenshot pass before handoff:

```bash
scripts/capture_release_screenshots.sh
```

x86_64 workstation screenshot pass for design review:

```bash
scripts/check_x86_emulator_host.sh
APPLY=1 scripts/prepare_x86_emulator.sh
scripts/capture_x86_release_screenshots.sh
```
