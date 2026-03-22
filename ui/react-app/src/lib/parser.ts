/*
 * Client-side parser for include/exclude filter expressions, matching Angular gtParseIncludesExcludes.
 */

export interface IncludesExcludes {
  includes: string[]
  excludes: string[]
}

export function gtParseIncludesExcludes(text: string): IncludesExcludes {
  const includes: string[] = []
  const excludes: string[] = []
  if (!text) {
    return { includes, excludes }
  }

  let curr = ''
  let inQuote = false
  let inExclude = false

  for (let i = 0; i < text.length; i++) {
    const c = text.charAt(i)
    if (c === ' ' || c === '\t' || c === '\r' || c === '\n') {
      if (inQuote) {
        curr += c
      } else {
        if (inExclude && curr.length === 0) {
          throw new Error('Invalid location for minus')
        }
        if (curr.length > 0) {
          if (inExclude) {
            excludes.push(curr)
          } else {
            includes.push(curr)
          }
          curr = ''
          inExclude = false
        }
      }
    } else if (c === '"') {
      if (inQuote) {
        inQuote = false
        if (curr.length > 0) {
          if (inExclude) {
            excludes.push(curr)
          } else {
            includes.push(curr)
          }
          curr = ''
          inExclude = false
        } else {
          inExclude = false
        }
      } else {
        inQuote = true
      }
    } else if (c === '-') {
      if (inQuote) {
        curr += c
      } else if (curr.length === 0 && !inExclude) {
        inExclude = true
      } else {
        throw new Error('Invalid location for minus')
      }
    } else {
      curr += c
    }
  }

  if (inQuote) {
    throw new Error('Mismatched quote')
  }
  if (inExclude && curr.length === 0) {
    throw new Error('Invalid location for minus')
  }
  if (curr.length > 0) {
    if (inExclude) {
      excludes.push(curr)
    } else {
      includes.push(curr)
    }
  }

  return { includes, excludes }
}
