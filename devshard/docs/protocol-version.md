# State root and protocol version

Devshard uses **two independent version concepts**. Conflating them breaks recovery, host binding, migration, and operator mental models.

## Runtime version (versiond)

Governance lists **which devshard binaries may run** in `DevshardEscrowParams.approved_versions` (`name`, `binary` URL, `sha256`). versiond polls dapi `GET /versions`, downloads matching zips, and routes HTTP to `/devshard/<name>/…`.

At session bind, storage records this as `CreateSessionParams.Version` / `SessionMeta.Version` / host **`boundVersion`**. It identifies the running process build so only hosts on the **same** versiond runtime name participate in a session (storage returns `ErrSessionVersionConflict` on mismatch).

| Surface | Field / API | Value |
|---------|-------------|--------|
| Embedded dapi | `HostManager` ctor, `main.go` | `"v1"` → `types.LegacyRouteSessionVersion` |
| devshardd | `NewHostManager(..., runtimeVersion, ...)` | versiond child name (e.g. from `DEVSHARD_BINARY_VERSION`) |
| User / devshardctl | `VersionForRoutePrefix(routePrefix)` | `LegacyRouteSessionVersion` for `/v1/devshard`; else `/devshard/<name>` segment |
| Storage | `sessions.version` | Same bind tag as above |

This tag is **not** hashed into the state root and is **not** sent as `state_root_and_protocol_version` on settlement.

See [upgrade.md](./upgrade.md) and [params-dataflow.md](./params-dataflow.md).

## State root and protocol version

**Protocol version** is the tag in:

- `EscrowState.StateRootAndProtocolVersion` (state machine; set via `WithStateRootAndProtocolVersion` / `state.WithVersion`)
- `MsgSettleDevshardEscrow.state_root_and_protocol_version` (on-chain settlement)
- Settlement JSON from devshardctl / hosts

It is hashed into every state root:

```text
version_hash = sha256(state_root_and_protocol_version_utf8)
state_root     = sha256(host_stats_hash || fees_be || rest_hash || version_hash || phase_byte)
```

All hosts in a session must use the **same** protocol tag for a given escrow lifetime or signatures and settlement quorum will not align.

| Surface | How it is set |
|---------|----------------|
| New host SM | `state.WithVersion(types.EffectiveStateRootAndProtocolVersion())` in dapi `HostManager`, user `RecoverSession` / `NewHTTPSession` |
| Default in `NewStateMachine` | `types.DevshardStateRootAndProtocolVersion` until opts override |
| Release binary | Link-time `DEVSHARD_PROTOCOL_VERSION` → `types.EffectiveStateRootAndProtocolVersion()` |
| dapi stats detail | `state_root_and_protocol_version` (not the `version` field) |

The default in source is `types.DevshardStateRootAndProtocolVersion` in `devshard/types/domain.go` (currently `"v2"`). `make devshardd-build` writes `build/devshard-protocol-version` with the same value for Testermint settlement assertions.

```bash
make devshardd-build DEVSHARD_PROTOCOL_VERSION=v2   # stamp + ldflags
cat build/devshard-protocol-version               # Testermint reads this
```

Tests that exercise host/storage binding use `devshard/internal/testutil.RuntimeTestVersion` (`"v1"`) for **`CreateSessionParams.Version` only**. Hash and settlement tests use `types.DevshardStateRootAndProtocolVersion` or `EffectiveStateRootAndProtocolVersion()`.

Implementation: `devshard/types/protocol_version.go`, `devshard/state/hash.go`, `devshard/state/settlement.go`, chain keeper `VerifyDevshardSettlement`.

## Legacy SQLite migration

`storage.MigrateLegacySQLite` copies `sessions.version` as the **runtime bind tag**. Rows with an empty legacy `version` default to `types.LegacyRouteSessionVersion` (`"v1"`), not the protocol tag.

## When to bump `DevshardStateRootAndProtocolVersion`

Change the constant in `domain.go` and release a **new devshard binary** when any of the following change incompatibly:

| Change type | Examples |
|-------------|----------|
| State-root composition | Preimage fields, `rest_hash` contents, sealed accumulator rules, inference record hashing, phase handling |
| Settlement protocol | Cleartext settlement fields, what hosts sign, keeper verification steps |

Do **not** bump this tag for ordinary release builds that only fix bugs without changing roots or settlement. Do **not** assume it must equal an `approved_versions.name` entry; those strings are unrelated purposes (binary identity vs protocol tag).

New sessions created after the bump stamp the new protocol tag on the state machine. Existing escrows keep the protocol tag they used until settled. Storage **runtime** bind rows are unchanged by a protocol bump unless operators roll out a new versiond name.

## Operator checklist

1. Implement the protocol change in `devshard/state` (and chain keeper if settlement rules change).
2. Increment `DevshardStateRootAndProtocolVersion` in `domain.go`.
3. Update hash/settlement/migration tests that hardcode the protocol tag.
4. Document the upgrade path for in-flight escrows (users must settle under the old protocol tag before deprecated behavior is removed, if applicable).
5. Roll out a new **versiond runtime name** only when changing the host binary package peers must run together—not when only the protocol tag changes.
