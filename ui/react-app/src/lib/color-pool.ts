/*
 * Keyed color pool for chart series — port of keyed-color-pools.js
 *
 * Each pool maps series labels to colors. Colors are preserved across refreshes
 * so a given series name keeps its color even as other series come and go.
 */

const FIXED_POOL = ['#edc240', '#8fc6ef', '#cb4b4b', '#4da74d', '#9440ed']

/**
 * Simple color lightening/darkening (replaces jQuery $.color.parse/scale).
 * Takes a hex color and multiplies each channel by the factor.
 */
function scaleColor(hex: string, factor: number): string {
  const r = Math.min(255, Math.round(parseInt(hex.slice(1, 3), 16) * factor))
  const g = Math.min(255, Math.round(parseInt(hex.slice(3, 5), 16) * factor))
  const b = Math.min(255, Math.round(parseInt(hex.slice(5, 7), 16) * factor))
  return (
    '#' +
    r.toString(16).padStart(2, '0') +
    g.toString(16).padStart(2, '0') +
    b.toString(16).padStart(2, '0')
  )
}

const dynamicPool = [...FIXED_POOL]
let variation = 1

function getColor(i: number): string {
  if (i < dynamicPool.length) {
    return dynamicPool[i]
  }
  const base = FIXED_POOL[i % FIXED_POOL.length]
  if (i % FIXED_POOL.length === 0 && i) {
    variation -= 0.3
    if (variation < 0.5) {
      variation += 1
    }
  }
  const color = scaleColor(base, variation)
  dynamicPool.push(color)
  return color
}

export interface KeyedColorPool {
  reset(keys: string[]): void
  get(key: string): string
  add(key: string): string
  remove(key: string): void
  keys(): string[]
}

export function createKeyedColorPool(): KeyedColorPool {
  let colors: Record<string, string> = {}
  let used: Record<string, boolean> = {}

  function nextAvailable(): string {
    for (let i = 0; i < dynamicPool.length; i++) {
      if (!used[dynamicPool[i]]) {
        return dynamicPool[i]
      }
    }
    return getColor(dynamicPool.length)
  }

  return {
    reset(keys: string[]) {
      const preserved: Record<string, string> = {}
      used = {}
      for (const key of keys) {
        const color = colors[key]
        if (color) {
          preserved[key] = color
          used[color] = true
        }
      }
      colors = preserved
      for (const key of keys) {
        if (key === 'Other') {
          colors[key] = '#888'
        } else if (!colors[key]) {
          const color = nextAvailable()
          colors[key] = color
          used[color] = true
        }
      }
    },
    keys() {
      return Object.keys(colors)
    },
    add(key: string): string {
      if (colors[key]) return colors[key]
      const color = nextAvailable()
      colors[key] = color
      used[color] = true
      return color
    },
    get(key: string): string {
      return colors[key]
    },
    remove(key: string) {
      const color = colors[key]
      if (color) {
        delete colors[key]
        delete used[color]
      }
    },
  }
}
