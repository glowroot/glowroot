/**
 * Shared validation regex patterns and number conversion utilities matching
 * Classic Glowroot (`app.js` $rootScope.pattern and `conversions.js`).
 */

const PERCENTILE_REGEXP = '([1-9]?[0-9]?(\\.[0-9]*)?|100(\\.0*)?)'

export const PATTERNS = {
  percentage: new RegExp('^' + PERCENTILE_REGEXP + '$'),
  percentageList: new RegExp(
    '^(' + PERCENTILE_REGEXP + ' *, *)*' + PERCENTILE_REGEXP + '$',
  ),
  integer: /^(0|[1-9][0-9]*)$/,
  positiveInteger: /^[1-9][0-9]*$/,
  /** Tolerant of missing whole (.2) and missing decimal (2.) */
  double: /^(0|[1-9][0-9]*)?(\.[0-9]*)?$/,
} as const

/**
 * Converts a string/input value to a number (or `null` when empty),
 * matching Classic Glowroot's `conversions.toNumber`.
 * Returns the original value if parsing yields NaN so callers/validation can catch it.
 */
export function toNumber(value: string | number | null | undefined): number | string | null {
  if (value === '' || value === null || value === undefined) {
    return null
  }
  if (typeof value === 'number') {
    return Number.isNaN(value) ? null : value
  }
  const float = parseFloat(value)
  if (Number.isNaN(float)) {
    return value
  }
  return float
}
