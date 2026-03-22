/*
 * JVM Gauges page — time-series chart of selected JVM gauges.
 *
 * Replaces gauge-values.js. First chart-bearing page wired to Phase 5 infrastructure.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { RefreshCw, ZoomOut } from 'lucide-react'
import { useAgent } from '../../contexts/AgentContext'
import { TimeSeriesChart, type PlotSeries } from '../../components/chart'
import { ChartRangeSelector } from '../../components/chart/ChartRangeSelector'
import { useChartRange } from '../../hooks/useChartRange'
import { useChartData } from '../../hooks/useChartData'
import { Input } from '../../components/ui/input'
import { HelpPopover } from '../../components/shared/HelpPopover'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'
import { formatGaugeValue } from '../../lib/formatting'

interface Gauge {
  name: string
  display: string
  displayParts: string[]
  shortDisplay?: string
  unit?: string
  grouping?: string
  counter: boolean
}

interface GaugeExtra {
  allGauges: Gauge[]
}

export function JvmGaugesPage() {
  const { agentRollup } = useAgent()
  const [searchParams, setSearchParams] = useSearchParams()
  const urlGaugeNames = searchParams.getAll('gauge-name')
  const urlGaugeKey = urlGaugeNames.join('\0')
  const defaultGaugeNames = agentRollup?.defaultGaugeNames ?? []

  const initializedDefaultsRef = useRef(
    urlGaugeNames.length > 0 || defaultGaugeNames.length > 0
  )
  const syncedGaugeKeyRef = useRef(urlGaugeKey)

  const [selectedGauges, setSelectedGauges] = useState<string[]>(() =>
    urlGaugeNames.length > 0 ? urlGaugeNames : defaultGaugeNames
  )
  const [gaugeFilter, setGaugeFilter] = useState('')

  useEffect(() => {
    const urlNames = searchParams.getAll('gauge-name')
    const currentKey = urlNames.join('\0')
    if (currentKey !== syncedGaugeKeyRef.current) {
      syncedGaugeKeyRef.current = currentKey
      if (urlNames.length > 0) {
        initializedDefaultsRef.current = true
      }
      setSelectedGauges(urlNames)
    }
  }, [searchParams])

  useEffect(() => {
    if (
      !initializedDefaultsRef.current &&
      searchParams.getAll('gauge-name').length === 0 &&
      agentRollup?.defaultGaugeNames?.length
    ) {
      initializedDefaultsRef.current = true
      setSelectedGauges(agentRollup.defaultGaugeNames)
    }
  }, [agentRollup, searchParams])

  function updateSelectedGauges(nextOrUpdater: string[] | ((prev: string[]) => string[])) {
    setSelectedGauges((prev) => {
      const next =
        typeof nextOrUpdater === 'function' ? nextOrUpdater(prev) : nextOrUpdater
      initializedDefaultsRef.current = true
      const nextKey = next.join('\0')
      if (nextKey !== syncedGaugeKeyRef.current) {
        syncedGaugeKeyRef.current = nextKey
        setSearchParams((prevParams) => {
          const updated = new URLSearchParams(prevParams)
          updated.delete('gauge-name')
          for (const g of next) {
            updated.append('gauge-name', g)
          }
          return updated
        })
      }
      return next
    })
  }

  const [range, rangeActions, refreshCounter] = useChartRange(
    4 * 60 * 60 * 1000, // default 4 hours for gauges
    { useGaugeViewThresholdMultiplier: true }
  )

  const addToQuery = useCallback(
    (query: Record<string, string | string[] | number | undefined>) => {
      query['gauge-name'] = selectedGauges.length > 0 ? selectedGauges : undefined
    },
    [selectedGauges]
  )

  const extractExtra = useCallback(
    (data: Record<string, unknown>): GaugeExtra => ({
      allGauges: (data.allGauges as Gauge[]) || [],
    }),
    []
  )

  const { chartData, loading, noData, error } = useChartData<GaugeExtra>({
    url: '/backend/jvm/gauges',
    range,
    refreshCounter,
    addToQuery,
    extractExtra,
    useGaugeViewThresholdMultiplier: true,
  })

  const allGauges = chartData?.extra.allGauges ?? []

  const gaugeMap = useMemo(() => {
    const map: Record<string, Gauge> = {}
    for (const g of allGauges) {
      map[g.name] = g
    }
    return map
  }, [allGauges])

  // Build short display names for gauges
  const shortDisplayMap = useMemo(() => {
    const map: Record<string, string> = {}
    if (allGauges.length === 0) return map
    const cloned = allGauges.map((g) => ({ ...g }))
    createShortDisplayNames(cloned)
    for (const gauge of cloned) {
      if (gauge.shortDisplay) {
        map[gauge.name] = gauge.shortDisplay
      }
    }
    return map
  }, [allGauges])

  const gaugeUnits = useMemo(() => {
    const units: Record<string, string> = {}
    for (const gauge of allGauges) {
      units[gauge.name] = gauge.unit ? ` ${gauge.unit}` : ''
    }
    return units
  }, [allGauges])

  // Filter the gauge list
  const filteredGauges = useMemo(() => {
    if (!gaugeFilter) return allGauges
    const lower = gaugeFilter.toLowerCase()
    return allGauges.filter((g) => g.display.toLowerCase().includes(lower))
  }, [allGauges, gaugeFilter])

  function toggleGauge(name: string) {
    updateSelectedGauges((prev) => {
      if (prev.includes(name)) {
        return prev.filter((n) => n !== name)
      }
      // Insert in server order
      const ordering = new Map(allGauges.map((g, i) => [g.name, i]))
      const next = [...prev, name]
      next.sort((a, b) => (ordering.get(a) ?? 0) - (ordering.get(b) ?? 0))
      return next
    })
  }

  // Scale series, record unscaled yvalMaps, compute yAxisLabel, yMax, and legend scales
  const { scaledSeries, yAxisLabel, yMax, yvalMaps } = useMemo(() => {
    const series = chartData?.dataSeries ?? []
    const rawMaps: Record<string, Record<number, number>> = {}
    for (const s of series) {
      const m: Record<number, number> = {}
      for (const pt of s.data) {
        if (pt != null && pt[1] != null) {
          m[pt[0]] = pt[1]
        }
      }
      rawMaps[s.name] = m
    }

    if (series.length === 0) {
      return {
        scaledSeries: series,
        yAxisLabel: '',
        yMax: undefined as number | undefined,
        yvalMaps: rawMaps,
      }
    }

    const isCpuLoad = (name: string) => {
      const g = gaugeMap[name]
      return (
        g?.grouping === 'grouping-cpu-load' ||
        name.endsWith(':ProcessCpuLoad') ||
        name.endsWith(':SystemCpuLoad')
      )
    }

    const units = series.map((s) => gaugeMap[s.name]?.unit ?? '')
    const allBytes = units.every((u) => u === 'bytes')
    const allCpuLoad = series.every((s) => isCpuLoad(s.name))
    const allSameNonEmptyUnit =
      !allBytes && units[0] !== '' && units.every((u) => u === units[0])

    if (allBytes) {
      const divisor = 1024 * 1024
      return {
        scaledSeries: series.map((s) => ({
          ...s,
          shortLabel: shortDisplayMap[s.name] || s.name,
          data: s.data.map((pt) =>
            pt == null ? null : ([pt[0], pt[1] == null ? null : pt[1] / divisor] as [number, number | null])
          ),
        })),
        yAxisLabel: 'MB',
        yMax: undefined as number | undefined,
        yvalMaps: rawMaps,
      }
    }

    if (allCpuLoad) {
      return {
        scaledSeries: series.map((s) => ({
          ...s,
          shortLabel: shortDisplayMap[s.name] || s.name,
          data: s.data.map((pt) =>
            pt == null
              ? null
              : ([pt[0], pt[1] == null ? null : pt[1] < 0 ? 0 : pt[1] * 100] as [number, number | null])
          ),
        })),
        yAxisLabel: '',
        yMax: 100,
        yvalMaps: rawMaps,
      }
    }

    if (allSameNonEmptyUnit || (series.length === 1 && units[0] !== '')) {
      return {
        scaledSeries: series.map((s) => ({
          ...s,
          shortLabel: shortDisplayMap[s.name] || s.name,
        })),
        yAxisLabel: units[0].trim(),
        yMax: undefined as number | undefined,
        yvalMaps: rawMaps,
      }
    }

    if (series.length === 1 && units[0] === '') {
      return {
        scaledSeries: series.map((s) => ({
          ...s,
          shortLabel: shortDisplayMap[s.name] || s.name,
          data: s.data.map((pt) =>
            pt == null
              ? null
              : ([pt[0], pt[1] == null ? null : pt[1] < 0 ? 0 : pt[1]] as [number, number | null])
          ),
        })),
        yAxisLabel: '',
        yMax: undefined as number | undefined,
        yvalMaps: rawMaps,
      }
    }

    // Mixed units / multiple ungrouped gauges: normalize each grouping to (0, 100]
    const groupScaleInfo: Record<string, { scale: number; scaleText: string }> = {}
    for (const s of series) {
      const groupKey = gaugeMap[s.name]?.grouping || s.name
      if (!groupScaleInfo[groupKey]) {
        groupScaleInfo[groupKey] = { scale: 1, scaleText: 'no data' }
      }
    }

    const groupStats: Record<string, { hasData: boolean; maxY: number }> = {}
    for (const s of series) {
      const groupKey = gaugeMap[s.name]?.grouping || s.name
      if (!groupStats[groupKey]) {
        groupStats[groupKey] = { hasData: false, maxY: 0 }
      }
      for (const pt of s.data) {
        if (pt == null || pt[1] == null) continue
        if (pt[0] < range.chartFrom || pt[0] > range.chartTo) continue
        if (pt[1] < 0) continue
        groupStats[groupKey].hasData = true
        if (pt[1] > groupStats[groupKey].maxY) {
          groupStats[groupKey].maxY = pt[1]
        }
      }
    }

    for (const [groupKey, stats] of Object.entries(groupStats)) {
      if (!stats.hasData) {
        groupScaleInfo[groupKey] = { scale: 1, scaleText: 'no data' }
      } else if (stats.maxY === 0) {
        groupScaleInfo[groupKey] = { scale: 1, scaleText: '1' }
      } else {
        const exp = 2 - Math.ceil(Math.log10(stats.maxY))
        const scale = Math.pow(10, exp)
        let scaleText: string
        if (exp === 0) {
          scaleText = '1'
        } else if (exp === 1) {
          scaleText = '10'
        } else if (exp === 2) {
          scaleText = '100'
        } else if (exp === -1) {
          scaleText = '1/10'
        } else if (exp === -2) {
          scaleText = '1/100'
        } else {
          scaleText = `10^${exp}`
        }
        groupScaleInfo[groupKey] = { scale, scaleText }
      }
    }

    const showGaugeScales = series.length > 1
    return {
      scaledSeries: series.map((s) => {
        const groupKey = gaugeMap[s.name]?.grouping || s.name
        const { scale, scaleText } = groupScaleInfo[groupKey]
        const baseLabel = shortDisplayMap[s.name] || s.name
        return {
          ...s,
          shortLabel: showGaugeScales ? `${baseLabel} (\u00D7${scaleText})` : baseLabel,
          data: s.data.map((pt) =>
            pt == null
              ? null
              : ([pt[0], pt[1] == null ? null : pt[1] < 0 ? 0 : pt[1] * scale] as [number, number | null])
          ),
        }
      }),
      yAxisLabel: '',
      yMax: 100,
      yvalMaps: rawMaps,
    }
  }, [chartData, gaugeMap, shortDisplayMap, range.chartFrom, range.chartTo])

  // Custom tooltip using raw unscaled yvalMaps and Classic interval wording
  const tooltipContent = useCallback(
    (
      from: number,
      to: number | null,
      _dataIndex: number,
      seriesIndex: number,
      allSeries: PlotSeries[]
    ) => {
      function smartTimeStr(ms: number): string {
        const d = new Date(ms)
        if (ms % 60000 === 0) {
          return d.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })
        }
        return d.toLocaleTimeString()
      }
      const interval = chartData?.dataPointIntervalMillis ?? 0
      const targetTs = to ?? from
      const isSingle5s = interval === 5000 && allSeries.length === 1
      let html = '<div style="font-weight:600;margin-bottom:2px">'
      if (isSingle5s) {
        html += smartTimeStr(targetTs)
      } else {
        const bucketFrom =
          interval > 0 ? Math.ceil((targetTs - interval) / interval) * interval : from
        html += `${smartTimeStr(bucketFrom)} to ${smartTimeStr(targetTs)}`
        html += '<span style="font-weight:400"> (average value over this interval)</span>'
      }
      html += '</div><table style="border-spacing:4px 1px">'
      for (let si = 0; si < allSeries.length; si++) {
        const s = allSeries[si]
        const rawVal = yvalMaps[s.name]?.[targetTs]
        let display: string
        if (rawVal === undefined) {
          display = 'no data'
        } else if (
          rawVal < 0 &&
          (gaugeMap[s.name]?.grouping === 'grouping-cpu-load' ||
            s.name.endsWith(':ProcessCpuLoad') ||
            s.name.endsWith(':SystemCpuLoad'))
        ) {
          display = 'unavailable'
        } else {
          display = formatGaugeValue(rawVal) + (gaugeUnits[s.name] || '')
        }
        const label = shortDisplayMap[s.name] || s.name || 'Other'
        html += `<tr${si === seriesIndex ? ' style="background:#eee"' : ''}>`
        html += `<td><span style="display:inline-block;width:10px;height:10px;background:${s.color};border:1px solid #ccc"></span></td>`
        html += `<td>${label}</td>`
        html += `<td style="font-weight:600">${display}</td>`
        html += '</tr>'
      }
      html += '</table>'
      return html
    },
    [chartData?.dataPointIntervalMillis, yvalMaps, gaugeMap, shortDisplayMap, gaugeUnits]
  )

  if (loading && !chartData) return <PageSpinner />
  if (error && !chartData) return <HttpError error={error} />

  const isFiltered = gaugeFilter.trim() !== '' && filteredGauges.length < allGauges.length

  return (
    <div className="space-y-4">
      <ChartRangeSelector range={range} actions={rangeActions} />

      {/* Chart */}
      <div className="rounded-lg border bg-white p-4">
        <div className="flex items-center justify-end gap-1 mb-2">
          <button
            type="button"
            onClick={rangeActions.zoomOut}
            title="Zoom out"
            aria-label="Zoom out"
            className="rounded p-1.5 text-gray-500 hover:bg-gray-100 hover:text-gray-700"
          >
            <ZoomOut className="h-4 w-4" />
          </button>
          <button
            type="button"
            onClick={rangeActions.refresh}
            title="Refresh"
            aria-label="Refresh"
            className="rounded p-1.5 text-gray-500 hover:bg-gray-100 hover:text-gray-700"
          >
            <RefreshCw className="h-4 w-4" />
          </button>
          <HelpPopover title="Chart navigation" align="right">
            <div className="space-y-1 text-xs">
              <div className="font-semibold">Chart navigation tips</div>
              <ul className="list-disc pl-4 space-y-0.5">
                <li>Drag or double click to zoom in</li>
                <li>Use Ctrl + scroll wheel to zoom in and out</li>
              </ul>
            </div>
          </HelpPopover>
        </div>
        {chartData && scaledSeries.length > 0 ? (
          <TimeSeriesChart
            dataSeries={scaledSeries}
            chartFrom={range.chartFrom}
            chartTo={range.chartTo}
            dataPointIntervalMillis={chartData.dataPointIntervalMillis}
            markings={chartData.markings}
            stacked={false}
            yAxisLabel={yAxisLabel}
            yMax={yMax}
            tooltipContent={tooltipContent}
            onZoom={rangeActions.handleZoom}
            onSelection={rangeActions.handleSelection}
          />
        ) : (
          <div className="flex items-center justify-center h-[300px] text-sm text-gray-500">
            {allGauges.length === 0
              ? 'No gauges captured in this time period'
              : selectedGauges.length === 0
                ? 'Select one or more gauges below'
                : noData
                  ? 'No data'
                  : 'Loading...'}
          </div>
        )}
      </div>

      {/* Gauge selector */}
      <div className="rounded-lg border bg-white p-4">
        <div className="flex items-center gap-2 mb-3">
          <Input
            placeholder="Filter gauges..."
            value={gaugeFilter}
            onChange={(e) => setGaugeFilter(e.target.value)}
            className="max-w-xs"
          />
          <button
            type="button"
            className="text-xs text-blue-600 hover:underline"
            onClick={() =>
              updateSelectedGauges((prev) => {
                const ordering = new Map(allGauges.map((g, i) => [g.name, i]))
                const next = Array.from(
                  new Set([...prev, ...filteredGauges.map((g) => g.name)])
                )
                next.sort((a, b) => (ordering.get(a) ?? 0) - (ordering.get(b) ?? 0))
                return next
              })
            }
          >
            {isFiltered ? 'Select all showing' : 'Select all'}
          </button>
          <button
            type="button"
            className="text-xs text-blue-600 hover:underline"
            onClick={() => {
              const filtered = new Set(filteredGauges.map((g) => g.name))
              updateSelectedGauges((prev) => prev.filter((n) => !filtered.has(n)))
            }}
          >
            {isFiltered ? 'Deselect all showing' : 'Deselect all'}
          </button>
        </div>
        <div className="space-y-0.5 max-h-80 overflow-y-auto">
          {filteredGauges.map((gauge) => {
            const selected = selectedGauges.includes(gauge.name)
            return (
              <label
                key={gauge.name}
                className="flex items-center gap-2 py-0.5 px-1 rounded hover:bg-gray-50 cursor-pointer text-sm"
              >
                <input
                  type="checkbox"
                  checked={selected}
                  onChange={() => toggleGauge(gauge.name)}
                />
                <span className={selected ? 'font-bold' : 'font-normal'}>
                  {gauge.display}
                </span>
              </label>
            )
          })}
          {filteredGauges.length === 0 && (
            <p className="text-sm text-gray-500 py-2">No gauges found.</p>
          )}
        </div>
      </div>
    </div>
  )
}

/**
 * Create short display names for gauges (matching the AngularJS logic).
 */
function createShortDisplayNames(gauges: Gauge[]) {
  const SEPARATOR = ' / '
  const splitNames = gauges.map((g) => g.displayParts)

  for (let i = 0; i < gauges.length; i++) {
    const parts = splitNames[i]
    const name = gauges[i].name
    const separator = name.lastIndexOf(SEPARATOR)
    let minRequired =
      name.substring(separator + 1).split(SEPARATOR).length + 1

    for (let j = 0; j < gauges.length; j++) {
      if (j === i) continue
      minRequired = Math.max(
        minRequired,
        numSamePartsFromEnd(parts, splitNames[j]) + 1
      )
    }
    gauges[i].shortDisplay = parts.slice(-minRequired).join(SEPARATOR)
  }
}

function numSamePartsFromEnd(a: string[], b: string[]): number {
  let k = 0
  while (
    k < Math.min(a.length, b.length) &&
    a[a.length - 1 - k] === b[b.length - 1 - k]
  ) {
    k++
  }
  return k
}
