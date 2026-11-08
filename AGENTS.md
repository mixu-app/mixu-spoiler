# mixu-spoiler agent guide

## Scope and authority

- Governs only this repository. Workspace rules in `../AGENTS.md` apply.
- Public repository, **Apache-2.0**. New files start with
  `// SPDX-License-Identifier: Apache-2.0`.
- Depends on `mixu-graph` (model only) and `mixu-contracts` (schema). Must not
  depend on `mixu-client`, `mixu-renderer`, `mixu-server` or Android/Compose.
- Design contract: `docs/design.md`. Prose authority:
  `mixu-docs` → `docs/security/privacy-and-rights.md`.

## What this library is

A deterministic function from `(record, horizon)` to `ALLOW(trimmed)` or
`DENY(reason)`. It is not a prompt, not a model guardrail, and not an
authorization system.

## Non-negotiable rules

1. **Fail closed.** Unknown edition, unparseable ordinal, missing horizon or
   unsupported schema version all deny. Never default to allow.
2. **Deny reasons carry no content.** Only stable codes
   (`BEYOND_HORIZON`, `UNKNOWN_EDITION`, `MALFORMED_REGION`, `OVERLAY_HIDDEN`,
   `SCHEMA_UNSUPPORTED`). No titles, names, ordinals or chapter hints.
3. **One policy object.** Graph projection, full-text search, autocomplete,
   evidence preview, node counts and Copilot retrieval context use the same
   implementation and the same test matrix. A surface with its own copy is a
   defect.
4. **Return trimmed values, never "full value + hide flag".** A caller must not
   be able to render the denied part by ignoring a boolean.
5. **Overlay visibility is part of the policy**, not the renderer's business:
   notes and hypothesis edges created after the view position are hidden by
   default.

## What this library does not solve (state it, do not imply otherwise)

- Model prior knowledge of a famous plot. That is product risk R-01; mitigation
  is adversarial evaluation plus a conservative "cannot verify from what you have
  read" fallback.
- A malicious client that patches the filter out. The server re-validates the
  horizon independently before returning retrieval results or graph slices.
- Rights. Whether a graph may be shown at all is a licensing question.

## Testing

- Matrix in `docs/design.md` (delayed identity, unreliable narrator, conflicting
  testimony, historical replay, Copilot, malformed edge). Each row is a test.
- Fixtures come from `mixu-fixtures`; for every row, break the filter on purpose,
  confirm the test fails, restore.
- Property-style checks are welcome: for random `r`, no returned identifier may
  have `first_reveal > r`.

## Gates

```sh
just check      # markdownlint + gradle check
```

A gate that cannot run fails loudly. A missing or broken tool runs from the pinned
container (`just <tool>-docker`) instead of being installed system-wide or skipped.

## CarryCtx

- `cd` here before `carryctx` or Git; branches `ctx-XXXX/<type>-<slug>`.
- Export `CARRYCTX_TASK` / `CARRYCTX_AGENT` before committing.
- No commit, push or release without authorization.

## Review checklist

1. Does any new path return an object without passing through the policy?
2. Can a deny reason be used to infer the hidden content?
3. Do search, counts and retrieval use the same policy instance?
4. Is the failure mode deny, not allow?
5. Is there a test that fails without this change?
