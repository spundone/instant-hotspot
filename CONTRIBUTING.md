# Contributing

Thanks for contributing to Instant Hotspot.

## Ground rules

- Keep changes small and focused.
- Target PRs to `main`.
- Include clear reproduction steps for bug fixes.
- Test on at least one host + one controller device when changing BLE/tethering behavior.

## Local setup

1. Use JDK 17 or 21.
2. Install Android SDK platform/build tools for API 35.
3. Build:

```bash
./gradlew :app:assembleDebug
```

## Pull requests

- Fill out the PR template.
- Include:
  - What changed
  - Why
  - Test steps + results
  - Screenshots/video for UI changes

## Reporting bugs / feature requests

- Use GitHub issue templates.
- Include device/ROM details:
  - Host model + ROM
  - Controller model + ROM
  - Root/Magisk module status
  - App version + commit id shown in the app
