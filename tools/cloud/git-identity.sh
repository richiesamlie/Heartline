#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
# Optional helper: sets git user.name / user.email only when both HEARTLINE_GIT_NAME and
# HEARTLINE_GIT_EMAIL are explicitly exported. Never hardcodes or overwrites with upstream identity.
cd "${CLAUDE_PROJECT_DIR:-$(dirname "$0")/../..}" || exit 0
if [ -n "${HEARTLINE_GIT_NAME:-}" ] && [ -n "${HEARTLINE_GIT_EMAIL:-}" ]; then
  git config user.name "$HEARTLINE_GIT_NAME"
  git config user.email "$HEARTLINE_GIT_EMAIL"
fi
