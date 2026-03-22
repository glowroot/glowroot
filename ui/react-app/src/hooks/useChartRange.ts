/*
 * useChartRange — manages chart time range state.
 *
 * Stores chartFrom, chartTo, last (for "last N" mode) and provides
 * update functions. Replaces the $scope.range object from chart-range.js.
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useLayout } from '../contexts/LayoutContext'
import type { RollupConfig } from '../types/layout'
import {
  applyLast,
  updateRange as computeRange,
  RANGE_SELECTIONS,
} from '../lib/chart-utils'

export interface ChartRange {
  chartFrom: number
  chartTo: number
  last: number
}

export interface ChartRangeActions {
  /** Set to a specific "last N ms" preset */
  setLast(last: number): void
  /** Set to a custom date range (exits "last" mode) */
  setCustomRange(from: number, to: number): void
  /** Handle zoom (from chart interaction) */
  handleZoom(from: number, to: number, zoomingOut: boolean): void
  /** Handle selection (from chart brush) */
  handleSelection(from: number, to: number): void
  /** Zoom out by 2x */
  zoomOut(): void
  /** Force refresh (re-apply "last" or increment refresh counter) */
  refresh(): void
}

function getRangeUrlKey(params: URLSearchParams): string {
  return `${params.get('last') ?? ''}|${params.get('from') ?? ''}|${params.get('to') ?? ''}`
}

function deriveRangeFromSearchParams(
  params: URLSearchParams,
  defaultLast: number,
  rollupConfigs: RollupConfig[],
  rollupExpirationMillis: number[],
  useGauge?: boolean
): ChartRange {
  const lastParam = params.get('last')
  if (lastParam !== null) {
    const lastNum = Number(lastParam)
    if (Number.isFinite(lastNum) && lastNum > 0) {
      const applied = applyLast(lastNum, rollupConfigs, rollupExpirationMillis, useGauge)
      return { ...applied, last: lastNum }
    }
  }
  const fromParam = params.get('from')
  const toParam = params.get('to')
  if (fromParam !== null && toParam !== null) {
    const fromNum = Number(fromParam)
    const toNum = Number(toParam)
    if (Number.isFinite(fromNum) && Number.isFinite(toNum) && fromNum < toNum) {
      return { chartFrom: fromNum, chartTo: toNum, last: 0 }
    }
  }
  const applied = applyLast(defaultLast, rollupConfigs, rollupExpirationMillis, useGauge)
  return { ...applied, last: defaultLast }
}

export function useChartRange(
  initialLast?: number,
  options?: {
    useGaugeViewThresholdMultiplier?: boolean
    tracePoints?: boolean
  }
): [ChartRange, ChartRangeActions, number] {
  const { layout } = useLayout()
  const { rollupConfigs, rollupExpirationMillis } = layout
  const useGauge = options?.useGaugeViewThresholdMultiplier
  const tracePoints = options?.tracePoints
  const [searchParams, setSearchParams] = useSearchParams()

  // Default to the first default range if the user's agent has one configured
  const defaultLast = initialLast ?? RANGE_SELECTIONS[5] // 24 hours

  const [range, setRange] = useState<ChartRange>(() =>
    deriveRangeFromSearchParams(
      searchParams,
      defaultLast,
      rollupConfigs,
      rollupExpirationMillis,
      useGauge
    )
  )

  // Refresh counter — incremented on any change to trigger data re-fetch
  const [refreshCounter, setRefreshCounter] = useState(0)
  const rangeRef = useRef(range)
  rangeRef.current = range

  const syncedUrlKeyRef = useRef(getRangeUrlKey(searchParams))

  const applyAndSyncRange = useCallback(
    (next: ChartRange) => {
      setRange(next)
      setRefreshCounter((c) => c + 1)

      let lastVal = ''
      let fromVal = ''
      let toVal = ''
      if (next.last > 0) {
        if (next.last !== defaultLast) {
          lastVal = String(next.last)
        }
      } else {
        fromVal = String(Math.round(next.chartFrom))
        toVal = String(Math.round(next.chartTo))
      }
      const nextUrlKey = `${lastVal}|${fromVal}|${toVal}`
      if (nextUrlKey === syncedUrlKeyRef.current) {
        return
      }
      syncedUrlKeyRef.current = nextUrlKey
      setSearchParams((prev) => {
        const updated = new URLSearchParams(prev)
        if (lastVal) {
          updated.set('last', lastVal)
        } else {
          updated.delete('last')
        }
        if (fromVal && toVal) {
          updated.set('from', fromVal)
          updated.set('to', toVal)
        } else {
          updated.delete('from')
          updated.delete('to')
        }
        return updated
      })
    },
    [defaultLast, setSearchParams]
  )

  // Sync range on external URL changes (e.g., browser Back/Forward)
  useEffect(() => {
    const currentUrlKey = getRangeUrlKey(searchParams)
    if (currentUrlKey === syncedUrlKeyRef.current) return
    syncedUrlKeyRef.current = currentUrlKey
    setRange(
      deriveRangeFromSearchParams(
        searchParams,
        defaultLast,
        rollupConfigs,
        rollupExpirationMillis,
        useGauge
      )
    )
    setRefreshCounter((c) => c + 1)
  }, [searchParams, defaultLast, rollupConfigs, rollupExpirationMillis, useGauge])

  const setLast = useCallback(
    (last: number) => {
      const applied = applyLast(
        last,
        rollupConfigs,
        rollupExpirationMillis,
        useGauge
      )
      applyAndSyncRange({ ...applied, last })
    },
    [rollupConfigs, rollupExpirationMillis, useGauge, applyAndSyncRange]
  )

  const setCustomRange = useCallback(
    (from: number, to: number) => {
      applyAndSyncRange({ chartFrom: from, chartTo: to, last: 0 })
    },
    [applyAndSyncRange]
  )

  const handleZoom = useCallback(
    (from: number, to: number, zoomingOut: boolean) => {
      const result = computeRange(
        from,
        to,
        rangeRef.current.last,
        rollupConfigs,
        rollupExpirationMillis,
        zoomingOut,
        false,
        useGauge,
        tracePoints
      )
      applyAndSyncRange(result)
    },
    [rollupConfigs, rollupExpirationMillis, useGauge, tracePoints, applyAndSyncRange]
  )

  const handleSelection = useCallback(
    (from: number, to: number) => {
      const result = computeRange(
        from,
        to,
        rangeRef.current.last,
        rollupConfigs,
        rollupExpirationMillis,
        false,
        true,
        useGauge,
        tracePoints
      )
      applyAndSyncRange(result)
    },
    [rollupConfigs, rollupExpirationMillis, useGauge, tracePoints, applyAndSyncRange]
  )

  const zoomOut = useCallback(() => {
    const { chartFrom, chartTo } = rangeRef.current
    const currRange = chartTo - chartFrom
    const result = computeRange(
      chartFrom - currRange / 2,
      chartTo + currRange / 2,
      rangeRef.current.last,
      rollupConfigs,
      rollupExpirationMillis,
      true,
      false,
      useGauge,
      tracePoints
    )
    applyAndSyncRange(result)
  }, [rollupConfigs, rollupExpirationMillis, useGauge, tracePoints, applyAndSyncRange])

  const refresh = useCallback(() => {
    const r = rangeRef.current
    if (r.last) {
      const applied = applyLast(
        r.last,
        rollupConfigs,
        rollupExpirationMillis,
        useGauge
      )
      setRange({ ...applied, last: r.last })
    }
    setRefreshCounter((c) => c + 1)
  }, [rollupConfigs, rollupExpirationMillis, useGauge])

  const actions: ChartRangeActions = {
    setLast,
    setCustomRange,
    handleZoom,
    handleSelection,
    zoomOut,
    refresh,
  }

  return [range, actions, refreshCounter]
}
