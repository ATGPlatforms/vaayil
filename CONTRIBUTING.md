# Contributing to Vaayil

Thanks for wanting to help. Contributions are accepted on equal terms: you keep the
copyright in your work, and every commit carries your sign-off.

## Reporting bugs

Use [GitHub Issues](https://github.com/ATGPlatforms/vaayil/issues). Include the app
version, the Android version and device model, what you did, what you expected and what
happened. Never paste a WireGuard private key, a preshared key or a full `.conf` file
into an issue — redact keys first. Security problems go through `SECURITY.md`, never a
public issue.

## Developer Certificate of Origin

Every commit must be signed off under the
[Developer Certificate of Origin](https://developercertificate.org/): a statement that
you wrote the change, or otherwise have the right to submit it under the project's
licence. Sign off with:

```bash
git commit -s
```

which appends a `Signed-off-by` trailer naming you. The name and address in the trailer
must match the commit's author; commits without a matching sign-off are not accepted.

There is no CLA and no transfer of rights to the project.

AI-assisted contributions are welcome on the same terms as the project's own: you have
reviewed and tested the change, and you sign off as the person responsible for it. The
project's own agent-made commits carry the maintainer's sign-off as the responsible
person, plus an AI co-author trailer.

## Commit messages

Conventional commits with a type and a short imperative summary, for example:

- `feat:` a new feature
- `fix:` a bug fix
- `docs:` documentation
- `test:` tests
- `chore:` maintenance

Keep commits and pull requests small and focused.

## Building and testing

Prerequisites:

- **JDK 17** — required by the Gradle toolchain.
- **Android SDK** — compileSdk/targetSdk 36, minSdk 26, Android Gradle Plugin 8.10.x.
- **NDK 27.0.12077973** and **CMake 3.22.1** — the vendored
  `:third-party:wireguard-tunnel` module builds its native library from source.
- **`make` and a host C compiler** (`cc`) on `PATH`.
- The first native build downloads **Go 1.24.3** into the Gradle user home, checked
  against a pinned SHA-256, and can take several minutes.

If your JDK or Android SDK lives outside the system paths:

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
export PATH=$JAVA_HOME/bin:$PATH
```

Focused tests:

```bash
./gradlew :module:test                # pure-JVM modules
./gradlew :module:testDebugUnitTest   # Android modules
```

The full gate — run both before opening a pull request:

```bash
./gradlew build
./gradlew :app:verifyRoborazziDebug
```

`build` compiles every module, runs the unit tests and lint, and includes the vendored
module's Go tests. `:app:verifyRoborazziDebug` compares the screenshot fixtures against
the committed ones; if your change intentionally alters the UI, re-record those fixtures
with `./gradlew :app:recordRoborazziDebug` and commit them.

## House rules

- **Module purity.** `:core:model`, `:core:config`, `:core:tunnel-api` and `:core:dns`
  are platform-free Kotlin/JVM code and must not import `android.*` or `androidx.*`.
  `AGENTS.md` records the reviewed dependency graph.
- **Dependency licences.** Apache-2.0 / MIT / BSD-class only. Check a new dependency's
  licence before adding it.
- **Tests first.** Write a failing test before the implementation.
- **UI work** follows the Vellum design system in `DESIGN.md`. `AGENTS.md` holds the
  architecture invariants contributors must respect.

## Code of conduct

Participation is governed by `CODE_OF_CONDUCT.md`.
