package logging

import (
	"log/slog"
	"os"
	"strings"
)

// ParseSlogLevel maps DEVSHARD_LOG_LEVEL (or SLOG_LEVEL when DEVSHARD is unset) to slog levels.
func ParseSlogLevel() slog.Level {
	raw := strings.TrimSpace(os.Getenv("DEVSHARD_LOG_LEVEL"))
	if raw == "" {
		raw = strings.TrimSpace(os.Getenv("SLOG_LEVEL"))
	}
	switch strings.ToLower(raw) {
	case "debug":
		return slog.LevelDebug
	case "warn", "warning":
		return slog.LevelWarn
	case "error":
		return slog.LevelError
	default:
		return slog.LevelInfo
	}
}

// ConfigureSlogFromEnv installs the default slog handler at the level from ParseSlogLevel.
func ConfigureSlogFromEnv() slog.Level {
	level := ParseSlogLevel()
	slog.SetDefault(slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: level})))
	return level
}
