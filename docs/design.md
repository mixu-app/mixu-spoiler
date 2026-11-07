# Design: knowledge-horizon filtering

Status: **Proposed** · Owner: mixu-core · Consumes: `mixu-graph` model, `mixu-contracts`

## Threat model

The adversary is not only a malicious user. The most common failure is a
well-meaning developer adding a fast path that returns an unfiltered object.
Threats, in order of likelihood:

1. A new query path that forgets to filter (search, autocomplete, counts).
2. A derived artifact that leaks size or position (node count, layout slot,
   embedding neighbours).
3. A client that lies about its horizon to obtain future data.
4. A model that knows the ending from pretraining, independent of context.
5. A rights problem mistaken for a filtering problem.

(3) is answered server-side; (4) is *reduced*, never solved; (5) is out of scope
here. This library addresses (1) and (2), and provides the primitive (3) uses.

## Horizon semantics

Three values, never collapsed:

| Value | Meaning | Mutable by |
|---|---|---|
| `reading_position` | where the reader is looking now | page navigation |
| `knowledge_horizon` | furthest disclosure explicitly unlocked | reading forward past the horizon, with confirmation |
| `graph_view_position` | historical state being displayed | reveal slider, bounded by horizon |

A backward jump changes only `reading_position`. Rewinding the graph changes only
`graph_view_position`. Moving forward past the horizon requires an explicit
action; a page skip must not silently unlock every intermediate fact.

## Filter pipeline

```text
candidate record
  → resolve edition + ordinal         (unknown ⇒ DENY)
  → first_reveal <= horizon?          (no  ⇒ DENY)
  → identity link boundary <= horizon (no  ⇒ keep personas separate)
  → overlay visibility policy          (hidden ⇒ DENY)
  → redact fields not permitted at r   (labels, previews, counts)
  → ALLOW(trimmed record)
```

The pipeline is pure and synchronous. It takes no I/O, no clock, no locale — so
it is trivially testable and cannot "sometimes" be skipped for performance.

## Surfaces that must use it

| Surface | What leaks if it does not |
|---|---|
| Entity panel | a later alias names the culprit early |
| Graph renderer | node count or edge slot implies a hidden person |
| Full-text search | a hit quotes a future chapter |
| Autocomplete | suggests a name the reader has not met |
| Evidence preview | shows the passage that explains the trick |
| Copilot retrieval | hands the model the ending |
| Export/share | an exported overlay references unknown ids |

Each surface gets its own test in the matrix below; passing the entity-panel test
proves nothing about search.

## Test matrix

| Case | Horizon | Must be denied |
|---|---|---|
| Delayed identity | before merge ordinal | merged label, identity edge, combined timeline |
| Unreliable narrator | before retraction | corrected status, retraction reason |
| Conflicting testimony | before the second account | the contradiction itself, as a hint |
| Historical replay | view ordinal < horizon | notes created after the view position |
| Copilot | any | future anchor ids in citations, "you should notice" hints |
| Malformed edge | any | fabricated ordinal, missing edition, unknown schema version |

Negative test policy: for each row, break the filter deliberately (return the
unfiltered value), confirm the test fails, restore. A test that cannot fail is
decoration.

## Server-side revalidation

The client sends a claimed horizon; the server:

1. Loads the recorded maximum read-through horizon for that account and book.
2. Rejects a client ordinal above it unless an explicit unlock event exists.
3. Applies the same filtering logic to retrieval context, citations and any
   returned graph slice.
4. Returns `REVEAL_BOUNDARY_EXCEEDED` (see `mixu-contracts` error registry)
   without echoing the future content it refused to send.

## Deny reasons

`BEYOND_HORIZON`, `UNKNOWN_EDITION`, `MALFORMED_REGION`, `OVERLAY_HIDDEN`,
`SCHEMA_UNSUPPORTED`. Reasons are stable codes; they never carry titles, names or
ordinals in user-visible strings.

## Non-goals

- Rendering decisions (that is `mixu-renderer`, which consumes `ALLOW` results).
- Authorization or entitlement (server).
- Extraction quality (private worker).
- Proving the absence of model prior knowledge — documented as R-01 instead.
