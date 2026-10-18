# Contributing to Spool Scanner

Thanks for your interest in contributing!

## Getting Started

1. Fork the repository
2. Clone your fork and follow the [setup instructions](README.md#development)
3. Create a feature branch from `main`

## Development

- Kotlin, Jetpack Compose, MVVM architecture
- Run `./gradlew testDebugUnitTest assembleDebug` before opening a PR
- See [CLAUDE.md](CLAUDE.md) for detailed code style and architecture guidelines

## Submitting Changes

1. Open an issue first to discuss what you'd like to change
2. Create a pull request from your feature branch to `main`
3. Include a clear description of what changed and why
4. All PRs are reviewed before merging

## What to Contribute

- Bug fixes
- NFC compatibility reports for different phone models
- UI improvements
- Documentation improvements
- Translations

## Code Guidelines

- Follow existing code patterns and naming conventions
- Keep changes focused — one feature or fix per PR
- Test on a physical device with NFC if your change touches NFC code
- Don't commit keystores, tokens or any credentials

## Reporting Issues

Use [GitHub Issues](https://github.com/lordmuffin/spool-scanner/issues) for:
- Bug reports (include device model, Android version, steps to reproduce)
- Feature requests
- NFC compatibility reports (include phone model and NFC chipset)

## License

By contributing, you agree that your contributions will be licensed under the [MIT License](LICENSE).
