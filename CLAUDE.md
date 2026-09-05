# CLAUDE.md

Read [Build from source](README.md#build-from-source) for the supported toolchain and build
commands. [app/build.gradle](app/build.gradle) owns declared SDK, language, and dependency versions.
The [physical-headset data-safety rules](docs/client-sbs-evaluation.md#protect-the-installed-artemis-data)
apply to every agent. Read the local `AGENTS.md`, when present, for machine-specific guidance and
coding conventions; it is intentionally untracked and is not required for a fresh checkout.

Read [docs/android-xr-sbs.md](docs/android-xr-sbs.md) before changing rendering, surfaces,
Client SBS inference, or spatial UI. It is the current architecture and contract owner.
[docs/client-sbs-evaluation.md](docs/client-sbs-evaluation.md) owns reproducible testing and
historical model evidence.

Do not duplicate model families, runtime selection, geometry constants, or historical
experiments here. Keeping one set of owners prevents stale instructions from overriding the
production contracts.
