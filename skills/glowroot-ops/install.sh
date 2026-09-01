#!/usr/bin/env bash
# Install glowroot-ops globally
# Usage: ./install.sh

set -euo pipefail

SKILL_NAME="glowroot-ops"
REPO_TREE="https://github.com/glowroot/glowroot/tree/main/skills/glowroot-ops"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

install_manual() {
  local target_root="$1"
  local dest="${target_root}/${SKILL_NAME}"
  mkdir -p "$dest"
  cp -R "${SCRIPT_DIR}/." "$dest/"
  echo "  -> $dest"
}

echo "glowroot-ops installer"
echo ""

if command -v npx >/dev/null 2>&1; then
  echo "[1/2] npx skills add (global)..."
  if npx --yes skills@latest add "$REPO_TREE" --skill "$SKILL_NAME" --global --yes --copy; then
    echo "  npx install OK"
  else
    echo "  npx failed — manual copy"
    install_manual "${HOME}/.cursor/skills"
    install_manual "${HOME}/.claude/skills"
    install_manual "${HOME}/.agents/skills"
  fi
else
  echo "[1/2] npx not found — manual copy"
  install_manual "${HOME}/.cursor/skills"
  install_manual "${HOME}/.claude/skills"
  install_manual "${HOME}/.agents/skills"
fi

echo "[2/2] Cursor /glowroot-ops command (if ~/.cursor exists)..."
if [[ -d "${HOME}/.cursor" ]] || mkdir -p "${HOME}/.cursor/commands"; then
  cp -f "${SCRIPT_DIR}/integrations/cursor-command.md" "${HOME}/.cursor/commands/${SKILL_NAME}.md"
  echo "  -> ${HOME}/.cursor/commands/${SKILL_NAME}.md"
fi

echo ""
echo "Done. Try:"
echo "  Cursor:     /glowroot-ops <your question>"
echo "  Any agent:  paste PROMPT.md from ${SCRIPT_DIR}"
echo ""
echo "Wiki: https://github.com/glowroot/glowroot/wiki"
