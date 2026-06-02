# State root and protocol version

Devshard uses two independent version concepts. Conflating them breaks recovery tests, migration, and operator mental models.

## Runtime version (versiond)

Governance lists **which devshard binaries may run** in `DevshardEscrowParams.approved_versions` (`name`, `binary` URL, `sha256`). versiond polls dapi `GET /versions`, downloads matching zips, and routes HTTP to `/devshard/<name>/…`.

At session bind, storage records this as `CreateSessionParams.Version` / host **boundVersion**. It identifies the running process build for peer binding and routing, not the state-root algorithm. The legacy `/v1/devshard` mount uses `types.LegacyRouteSessionVersion` (`"v1"`) for that bind tag via `VersionForRoutePrefix`.

See [upgrade.md](./upgrade.md) and [params-dataflow.md](./params-dataflow.md).

## State root and protocol version

**Protocol version** is the tag in:

- `EscrowState.StateRootAndProtocolVersion` (state machine, set via `WithStateRootAndProtocolVersion`)
- `MsgSettleDevshardEscrow.state_root_and_protocol_version` (on-chain settlement)
- Legacy storage `sessions.version` when migrating SQLite

It is hashed into every state root:

```text
version_hash = sha256(state_root_and_protocol_version_utf8)
state_root     = sha256(host_stats_hash || fees_be || rest_hash || version_hash || phase_byte)
```

All hosts in a session must use the **same** protocol tag or signatures and settlement quorum will not align.

The default in source is `types.DevshardStateRootAndProtocolVersion` in `devshard/types/domain.go` (currently `"v2"`). Release binaries set the link-time variable via `DEVSHARD_PROTOCOL_VERSION` at build (`-X devshard/types.buildStateRootProtocolVersion=…`); hosts call `types.EffectiveStateRootAndProtocolVersion()` (no runtime env). `make devshardd-build` writes `build/devshard-protocol-version` with the same value for Testermint assertions.

```bash
make devshardd-build DEVSHARD_PROTOCOL_VERSION=v2   # stamp + ldflags
cat build/devshard-protocol-version               # Testermint reads this
```

Tests that exercise legacy composition often use `devshard/internal/testutil.RuntimeTestVersion` only for storage/runtime binding, not as a substitute for this tag in hash/settlement tests.

Implementation: `devshard/types/protocol_version.go`, `devshard/state/hash.go`, `devshard/state/settlement.go`, chain keeper `VerifyDevshardSettlement`.

## When to bump `DevshardStateRootAndProtocolVersion`

Change the constant in `domain.go` and release a **new devshard binary** when any of the following change incompatibly:

| Change type | Examples |
|-------------|----------|
| State-root composition | Preimage fields, `rest_hash` contents, sealed accumulator rules, inference record hashing, phase handling |
| Settlement protocol | Cleartext settlement fields, what hosts sign, keeper verification steps |

Do **not** bump this tag for ordinary release builds that only fix bugs without changing roots or settlement. Do **not** assume it must equal an `approved_versions.name` entry; those strings are unrelated purposes (binary identity vs protocol tag).

New sessions created after the bump stamp the new tag at bind. Existing sessions keep the tag they were created with until settled.

## Operator checklist

1. Implement the protocol change in `devshard/state` (and chain keeper if settlement rules change).
2. Increment `DevshardStateRootAndProtocolVersion` in `domain.go`.
3. Update hash/settlement/migration tests that hardcode the tag.
4. Document the upgrade path for in-flight escrows (users must settle under the old tag before deprecated behavior is removed, if applicable).
