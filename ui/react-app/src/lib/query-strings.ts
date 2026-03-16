/*
 * Query string utilities ported from query-strings.js
 *
 * The parameter ordering matches the classic UI: agent/transaction params first.
 */

const orderedKeys = [
  'agent-rollup-id',
  'agent-id',
  'transaction-type',
  'transaction-name',
]

export function encodeObject(obj: Record<string, unknown>): string {
  const parts: string[] = []

  function addKey(key: string) {
    const value = obj[key]
    if (value === undefined || value === null) {
      return
    }
    if (Array.isArray(value)) {
      for (const item of value) {
        if (item !== undefined && item !== null) {
          parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(String(item)))
        }
      }
    } else {
      parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(String(value)))
    }
  }

  // add ordered keys first
  for (const key of orderedKeys) {
    if (key in obj) {
      addKey(key)
    }
  }

  // then remaining keys
  for (const key of Object.keys(obj)) {
    if (!orderedKeys.includes(key)) {
      addKey(key)
    }
  }

  return parts.length ? '?' + parts.join('&') : ''
}

/**
 * Build an agent query string for navigation.
 * Returns e.g. "?agent-id=abc" or "?agent-rollup-id=xyz" or ""
 */
export function agentQueryString(
  central: boolean,
  agentId: string | null,
  agentRollupId: string | null,
  isRollup: boolean
): string {
  if (!central) {
    return ''
  }
  if (isRollup && agentRollupId) {
    return '?agent-rollup-id=' + encodeURIComponent(agentRollupId)
  }
  if (agentId) {
    return '?agent-id=' + encodeURIComponent(agentId)
  }
  return ''
}

/**
 * Check if an agent rollup ID represents a rollup (ends with "::")
 */
export function isRollupId(id: string): boolean {
  return id.endsWith('::')
}
