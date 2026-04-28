# Contributing

Thanks for your interest in improving the RoN community server system.

## Reporting bugs

Open an issue using the **Bug report** template. Include:

- Which component is affected (`ron-proxy`, `ron-lobby`, `ron-instance`, `ron-common`)
- Versions of: this project, RoN mod, Forge, Paper/Velocity, Java
- Steps to reproduce
- Relevant logs (proxy + instance + lobby — match the timestamps)

## Suggesting changes

For non-trivial changes, open an issue first to discuss the approach before sending a PR. Small fixes (typos, log clarifications, obvious bugs) can go straight to a PR.

## Pull requests

1. Fork the repo and create a branch from `main`.
2. Keep changes scoped — one logical change per PR.
3. Make sure `./gradlew build` passes at the repo root *and* in `ron-instance/`.
4. Don't modify the RoN mod itself — this project intentionally treats it as a black-box `compileOnly` dependency.
5. Don't commit binary jars (the RoN jar in `ron-instance/libs/` is gitignored).

## Building locally

```bash
./gradlew build                                            # ron-common, ron-proxy, ron-lobby
mkdir -p ron-instance/libs/common
cp ron-common/build/libs/ron-common-1.0.0.jar ron-instance/libs/common/
(cd ron-instance && ./gradlew build)
```

You'll need Java 17 and a copy of the RoN mod jar in `ron-instance/libs/`.

## Code style

- Java 17, UTF-8, 4-space indent.
- Keep `ron-lobby` minimal — it handles matchmaking only. Player effects (cosmetic restrictions, blinding, movement blocking) belong in other plugins.
- Prefer config options over hardcoded behavior when the choice could vary per server.

## License

By contributing, you agree that your contributions will be licensed under the [MIT License](LICENSE).
