package broker

import (
	"decentralized-api/logging"
	"fmt"
	"strings"

	"github.com/productscience/inference/x/inference/types"
)

// MLNodeLockCall carries correlation fields for ML node lock diagnostics.
type MLNodeLockCall struct {
	Path        string // e.g. execute, validate, inference
	InferenceID uint64
	EscrowID    string
	Nonce       uint64
}

func mergeMLNodeLockCall(opts []MLNodeLockCall) MLNodeLockCall {
	if len(opts) == 0 {
		return MLNodeLockCall{}
	}
	return opts[0]
}

func (c MLNodeLockCall) fields(extra ...any) []any {
	out := []any{
		"event", "ml_node_lock",
		"path", c.Path,
	}
	if c.EscrowID != "" {
		out = append(out, "escrow_id", c.EscrowID)
	}
	if c.InferenceID != 0 {
		out = append(out, "inference_id", c.InferenceID)
	}
	if c.Nonce != 0 {
		out = append(out, "nonce", c.Nonce)
	}
	return append(out, extra...)
}

// logNodePoolUnavailable logs why no node could be locked for the request.
func (b *Broker) logNodePoolUnavailable(model string, skipNodeIDs []string, call MLNodeLockCall) {
	if b == nil {
		return
	}
	epochState := b.phaseTracker.GetCurrentEpochState()
	if epochState.IsNilOrNotSynced() {
		logging.Debug("ml_lock_unavailable", types.Nodes, call.fields(
			"model", model,
			"reason", "epoch_state_not_synced",
		)...)
		return
	}

	skip := make(map[string]struct{}, len(skipNodeIDs))
	for _, id := range skipNodeIDs {
		if id != "" {
			skip[id] = struct{}{}
		}
	}

	b.mu.RLock()
	defer b.mu.RUnlock()

	var details []string
	for _, node := range b.nodes {
		if _, excluded := skip[node.Node.Id]; excluded {
			details = append(details, fmt.Sprintf("%s:skipped", node.Node.Id))
			continue
		}
		available, reason := b.nodeAvailable(node, model, epochState.LatestEpoch.EpochIndex, epochState.CurrentPhase)
		if available {
			details = append(details, fmt.Sprintf("%s:ok(lock_count=%d,max=%d)",
				node.Node.Id, node.State.LockCount, node.Node.MaxConcurrent))
		} else {
			details = append(details, fmt.Sprintf("%s:%s", node.Node.Id, reason))
		}
	}
	logging.Debug("ml_lock_unavailable", types.Nodes, call.fields(
		"model", model,
		"skip_node_ids", skipNodeIDs,
		"nodes", strings.Join(details, "; "),
	)...)
}
