/*
 * Chart utility functions — ports of chart range and rollup logic from charts.js
 */

import type { RollupConfig } from '../types/layout'

// Predefined range selections matching chart-range.js
export const RANGE_SELECTIONS = [
  30 * 60 * 1000, // 30 minutes
  60 * 60 * 1000, // 60 minutes
  2 * 60 * 60 * 1000, // 2 hours
  4 * 60 * 60 * 1000, // 4 hours
  8 * 60 * 60 * 1000, // 8 hours
  24 * 60 * 60 * 1000, // 24 hours
  2 * 24 * 60 * 60 * 1000, // 2 days
  7 * 24 * 60 * 60 * 1000, // 7 days
  30 * 24 * 60 * 60 * 1000, // 30 days
]

const MINUTE = 60 * 1000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

/**
 * Calculate the data point interval in milliseconds based on the time range,
 * rollup configuration, and expiration settings.
 *
 * This MUST match the server-side calculation.
 */
export function getDataPointIntervalMillis(
  from: number,
  to: number,
  rollupConfigs: RollupConfig[],
  rollupExpirationMillis: number[],
  useGaugeViewThresholdMultiplier?: boolean,
  tracePoints?: boolean
): number {
  const millis = to - from
  if (tracePoints && millis < 120000) {
    return 1000
  }
  const timeAgoMillis = Date.now() - from
  for (let i = 0; i < rollupConfigs.length - 1; i++) {
    let viewThresholdMillis = rollupConfigs[i + 1].viewThresholdMillis
    if (useGaugeViewThresholdMultiplier) {
      viewThresholdMillis *= 4
    }
    const expirationMillis = rollupExpirationMillis[i]
    if (
      millis < viewThresholdMillis &&
      (expirationMillis === 0 || expirationMillis > timeAgoMillis)
    ) {
      return rollupConfigs[i].intervalMillis
    }
  }
  return rollupConfigs[rollupConfigs.length - 1].intervalMillis
}

/**
 * Round "last" value up to a nice boundary.
 */
export function roundUpLast(last: number, selection?: boolean): number {
  if (last > DAY) {
    if (selection) {
      return Math.floor(last / HOUR) * HOUR
    }
    return Math.ceil(last / DAY) * DAY
  }
  if (last > HOUR && !selection) {
    return Math.ceil(last / HOUR) * HOUR
  }
  if (selection) {
    return Math.floor(last / MINUTE) * MINUTE
  }
  return Math.ceil(last / MINUTE) * MINUTE
}

/**
 * Apply "last N ms" to produce chartFrom/chartTo.
 */
export function applyLast(
  last: number,
  rollupConfigs: RollupConfig[],
  rollupExpirationMillis: number[],
  useGaugeViewThresholdMultiplier?: boolean
): { chartFrom: number; chartTo: number } {
  const now = Math.floor(Date.now() / 1000) * 1000 // startOf('second')
  const from = now - last
  const to = now + last / 10
  const dataPointIntervalMillis = getDataPointIntervalMillis(
    from,
    to,
    rollupConfigs,
    rollupExpirationMillis,
    useGaugeViewThresholdMultiplier
  )
  let revisedFrom =
    Math.floor(from / dataPointIntervalMillis) * dataPointIntervalMillis
  let revisedTo =
    Math.ceil(to / dataPointIntervalMillis) * dataPointIntervalMillis
  const revisedDataPointIntervalMillis = getDataPointIntervalMillis(
    revisedFrom,
    revisedTo,
    rollupConfigs,
    rollupExpirationMillis,
    useGaugeViewThresholdMultiplier
  )
  if (revisedDataPointIntervalMillis !== dataPointIntervalMillis) {
    revisedFrom =
      Math.floor(from / revisedDataPointIntervalMillis) *
      revisedDataPointIntervalMillis
    revisedTo =
      Math.ceil(to / revisedDataPointIntervalMillis) *
      revisedDataPointIntervalMillis
  }
  return { chartFrom: revisedFrom, chartTo: revisedTo }
}

/**
 * Compute updated range after a zoom or selection event.
 * Returns the new { chartFrom, chartTo, last }.
 */
export function updateRange(
  from: number,
  to: number,
  currentLast: number,
  rollupConfigs: RollupConfig[],
  rollupExpirationMillis: number[],
  zoomingOut: boolean,
  selection?: boolean,
  useGaugeViewThresholdMultiplier?: boolean,
  tracePoints?: boolean
): { chartFrom: number; chartTo: number; last: number } {
  if (zoomingOut && currentLast) {
    const newLast = roundUpLast(currentLast * 2)
    const applied = applyLast(
      newLast,
      rollupConfigs,
      rollupExpirationMillis,
      useGaugeViewThresholdMultiplier
    )
    return { ...applied, last: newLast }
  }

  const dataPointIntervalMillis = getDataPointIntervalMillis(
    from,
    to,
    rollupConfigs,
    rollupExpirationMillis,
    useGaugeViewThresholdMultiplier,
    tracePoints
  )

  let revisedFrom: number
  let revisedTo: number

  if (zoomingOut) {
    revisedFrom =
      Math.floor(from / dataPointIntervalMillis) * dataPointIntervalMillis
    revisedTo =
      Math.ceil(to / dataPointIntervalMillis) * dataPointIntervalMillis
    const revisedDPI = getDataPointIntervalMillis(
      revisedFrom,
      revisedTo,
      rollupConfigs,
      rollupExpirationMillis,
      useGaugeViewThresholdMultiplier,
      tracePoints
    )
    if (revisedDPI !== dataPointIntervalMillis) {
      revisedFrom = Math.floor(from / revisedDPI) * revisedDPI
      revisedTo = Math.ceil(to / revisedDPI) * revisedDPI
    }
  } else {
    revisedFrom =
      Math.ceil(from / dataPointIntervalMillis) * dataPointIntervalMillis
    revisedTo =
      Math.floor(to / dataPointIntervalMillis) * dataPointIntervalMillis
    if (revisedTo <= revisedFrom) {
      if (to - revisedTo > revisedFrom - from) {
        revisedTo += dataPointIntervalMillis
        revisedFrom = revisedTo - dataPointIntervalMillis
      } else {
        revisedFrom -= dataPointIntervalMillis
        revisedTo = revisedFrom + dataPointIntervalMillis
      }
    }
  }

  const now = Date.now()
  if (
    (revisedTo > now || to > now) &&
    (!tracePoints || revisedTo - revisedFrom >= 60000)
  ) {
    if (!zoomingOut && !selection && currentLast) {
      if (tracePoints && currentLast === 60000) {
        return {
          chartTo: Math.ceil(now / 60000) * 60000,
          chartFrom: Math.ceil(now / 60000) * 60000 - 60000,
          last: 0,
        }
      }
      const newLast = roundUpLast(currentLast / 2)
      const applied = applyLast(
        newLast,
        rollupConfigs,
        rollupExpirationMillis,
        useGaugeViewThresholdMultiplier
      )
      return { ...applied, last: newLast }
    }

    if (tracePoints && revisedTo - revisedFrom === 120000) {
      const applied = applyLast(
        60000,
        rollupConfigs,
        rollupExpirationMillis,
        useGaugeViewThresholdMultiplier
      )
      return { ...applied, last: 60000 }
    }

    const newLast = roundUpLast(now - revisedFrom, selection)
    if (newLast > 0) {
      const applied = applyLast(
        newLast,
        rollupConfigs,
        rollupExpirationMillis,
        useGaugeViewThresholdMultiplier
      )
      return { ...applied, last: newLast }
    }
  }

  return { chartFrom: revisedFrom, chartTo: revisedTo, last: 0 }
}

/**
 * Format a "last" duration for display (e.g., "Last 60 minutes", "Last 2 hours").
 */
export function formatLast(last: number): string {
  if (last === HOUR) return 'Last 60 minutes'
  if (last === DAY) return 'Last 24 hours'

  let display = 'Last'
  function append(num: number, label: string) {
    if (num) {
      display += ` ${num} ${label}${num !== 1 ? 's' : ''}`
    }
  }

  const days = Math.floor(last / DAY)
  const hours = Math.floor((last % DAY) / HOUR)
  const minutes = Math.floor((last % HOUR) / MINUTE)
  const seconds = Math.floor((last % MINUTE) / 1000)

  append(days, 'day')
  append(hours, 'hour')
  append(minutes, 'minute')
  append(seconds, 'second')

  return display
}

/**
 * Format a time range for display.
 */
export function formatTimeRange(chartFrom: number, chartTo: number): string {
  const from = Math.floor(chartFrom / 60000) * 60000
  const to = Math.ceil(chartTo / 60000) * 60000

  function fancyDate(ms: number): string {
    const today = new Date()
    today.setHours(0, 0, 0, 0)
    const d = new Date(ms)
    d.setHours(0, 0, 0, 0)
    if (d.getTime() === today.getTime()) return 'Today'
    if (d.getTime() === today.getTime() - DAY) return 'Yesterday'
    return new Date(ms).toLocaleDateString()
  }

  function timeStr(ms: number): string {
    return new Date(ms).toLocaleTimeString(undefined, {
      hour: 'numeric',
      minute: '2-digit',
    })
  }

  const fromDate = fancyDate(from)
  const toDate = fancyDate(to)
  if (fromDate === toDate) {
    return `${fromDate}, ${timeStr(from)} to ${timeStr(to)}`
  }
  return `${fromDate} ${timeStr(from)} to ${toDate} ${timeStr(to)}`
}

/**
 * Check if a data series set has any visible data points in the given range.
 */
export function isNoData(
  dataSeries: Array<{ data: Array<[number, number | null] | null> }>,
  from: number,
  to: number
): boolean {
  for (const series of dataSeries) {
    for (const point of series.data) {
      if (point && point[0] >= from && point[0] <= to) {
        return false
      }
    }
  }
  return true
}
