# Changelog

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- ADR-042 char-level horizon gating (`viewCharOffset`, `isDisclosedAtView`) in `Horizon` and
  `RedactionPolicy` (`INV-7`), conditional DSU collapse (`INV-3`), alias reveal isolation (`INV-6`),
  and `P-SPOILER-09` prefix-truncation equivalence coverage.
- Updated `libs/mixu-graph` submodule pin to `848270487489a147e22d9634add12511418d05a8`.
- Repository scaffolding: `AGENTS.md`, `repo.toml`, `.carryctx/config.toml`,
  `justfile` gates, CI workflow, `README.md` and `docs/design.md`.
- Documented the module boundary: this library is consumed by `mixu-client` as an
  Apache-2.0 dependency and must not depend on client or server code.
