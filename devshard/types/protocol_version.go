package types

import "strings"

// buildStateRootProtocolVersion is set at link time (see Makefile / Dockerfile
// DEVSHARD_PROTOCOL_VERSION). Empty in plain `go test` / `go run` falls back to
// DevshardStateRootAndProtocolVersion.
var buildStateRootProtocolVersion string

// EffectiveStateRootAndProtocolVersion returns the state-root / settlement protocol
// tag baked into this binary. Testermint reads the same value from
// build/devshard-protocol-version written by `make devshardd-build`.
func EffectiveStateRootAndProtocolVersion() string {
	if v := strings.TrimSpace(buildStateRootProtocolVersion); v != "" {
		return NormalizeVersion(v)
	}
	return DevshardStateRootAndProtocolVersion
}

// BuildStateRootProtocolVersion exposes the link-time tag for tests and tooling.
func BuildStateRootProtocolVersion() string {
	return buildStateRootProtocolVersion
}
