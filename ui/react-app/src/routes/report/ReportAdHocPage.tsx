/*
 * Ad hoc reporting page — date/agent/metric/rollup/timezone builder, chart, sortable table, and CSV export.
 *
 * Replaces report/adhoc.js & report/adhoc.html.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { ArrowDown, ArrowUp } from 'lucide-react'
import { useLayout } from '../../contexts/LayoutContext'
import { apiGet } from '../../lib/api'
import { formatCount, formatGaugeValue, formatMillis } from '../../lib/formatting'
import { TimeSeriesChart, type PlotSeries } from '../../components/chart'
import type { DataSeries } from '../../hooks/useChartData'
import { Button } from '../../components/ui/button'
import { GtSelectpicker } from '../../components/shared/GtSelectpicker'
import { HttpError } from '../../components/shared/HttpError'
import { createKeyedColorPool } from '../../lib/color-pool'

interface MetricOption {
  id: string
  display: string
  heading?: boolean
  disabled?: boolean
}

interface ReportAgentRollup {
  id: string
  display: string
  lastDisplayPart: string
  depth: number
}

interface GaugeInfo {
  name: string
  display: string
  unit?: string
}

interface TransactionTypesAndGaugesResponse {
  transactionTypes: string[]
  gauges: GaugeInfo[]
}

interface ReportDataSeries extends DataSeries {
  overall?: number
}

interface ReportResponse {
  dataSeries: ReportDataSeries[]
}

interface AppliedReport {
  agentRollupIds: string[]
  metric: string
  transactionType: string
  transactionName: string
  timerName: string
  percentile: string
  fromIso: string
  toIso: string
  rollup: 'hourly' | 'daily' | 'weekly' | 'monthly'
  timeZoneId: string
}

interface TableRow {
  label: string
  agentRollupId: string
  overall: number
  values: Record<number, number>
}

const BASE_METRICS: MetricOption[] = [
  { id: 'transaction', display: 'Transactions', heading: true, disabled: true },
  { id: 'transaction:average', display: 'Response time (average)' },
  { id: 'transaction:x-percentile', display: 'Response time (X\u1d57\u02b0 percentile)' },
  { id: 'transaction:timer-inclusive', display: 'Breakdown metric time (inclusive)' },
  { id: 'transaction:timer-exclusive', display: 'Breakdown metric time (exclusive)' },
  { id: 'transaction:timer-count', display: 'Breakdown metric count' },
  { id: 'transaction:count', display: 'Transaction count' },
  { id: '-empty1-', display: '', disabled: true },
  { id: 'error', display: 'Errors', heading: true, disabled: true },
  { id: 'error:rate', display: 'Error rate (%)' },
  { id: 'error:count', display: 'Error count' },
  { id: '-empty2-', display: '', disabled: true },
  { id: 'gauge', display: 'JVM Gauges', heading: true, disabled: true },
]

const FOUR_HOUR_TIME_ZONES = [
  'GMT-12:00',
  'GMT-8:00',
  'GMT-4:00',
  'GMT',
  'GMT+4:00',
  'GMT+8:00',
  'GMT+12:00',
  'GMT+16:00',
]

function showTransactionTypeAndName(metric: string): boolean {
  return metric.startsWith('transaction:') || metric.startsWith('error:')
}

function showTimerName(metric: string): boolean {
  return (
    metric === 'transaction:timer-inclusive' ||
    metric === 'transaction:timer-exclusive' ||
    metric === 'transaction:timer-count'
  )
}

function transactionNameRequired(metric: string): boolean {
  return (
    metric === 'transaction:timer-inclusive' ||
    metric === 'transaction:timer-exclusive'
  )
}

function toIsoDateLocal(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

function paramDateToIso(param: string | null): string | null {
  if (!param) return null
  if (/^\d{8}$/.test(param)) {
    return `${param.slice(0, 4)}-${param.slice(4, 6)}-${param.slice(6, 8)}`
  }
  if (/^\d{4}-\d{2}-\d{2}$/.test(param)) {
    return param
  }
  return null
}

function isoToCompact(iso: string): string {
  return iso.replace(/-/g, '')
}

function parseIsoParts(iso: string): [number, number, number] {
  const [y, m, d] = iso.split('-').map(Number)
  return [y || 1970, m || 1, d || 1]
}

function isoToLocalEpochMs(iso: string): number {
  const [y, m, d] = parseIsoParts(iso)
  return new Date(y, m - 1, d, 0, 0, 0, 0).getTime()
}

function addDaysIso(iso: string, days: number): string {
  const [y, m, d] = parseIsoParts(iso)
  const dt = new Date(Date.UTC(y, m - 1, d + days))
  return `${dt.getUTCFullYear()}-${String(dt.getUTCMonth() + 1).padStart(2, '0')}-${String(dt.getUTCDate()).padStart(2, '0')}`
}

function parseFixedGmtOffsetMinutes(timeZoneId: string): number | null {
  if (timeZoneId === 'GMT' || timeZoneId === 'UTC') return 0
  const m = /^GMT([+-])(\d{1,2})(?::(\d{2}))?$/.exec(timeZoneId)
  if (!m) return null
  const sign = m[1] === '-' ? -1 : 1
  const hours = parseInt(m[2], 10)
  const mins = m[3] ? parseInt(m[3], 10) : 0
  return sign * (hours * 60 + mins)
}

function getZoneOffsetMinutes(utcMs: number, timeZoneId: string): number {
  const fixed = parseFixedGmtOffsetMinutes(timeZoneId)
  if (fixed !== null) return fixed
  try {
    const dtf = new Intl.DateTimeFormat('en-US', {
      timeZone: timeZoneId,
      hour12: false,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    })
    const parts = dtf.formatToParts(new Date(utcMs))
    const get = (t: string) => Number(parts.find((p) => p.type === t)?.value ?? '0')
    const h = get('hour') === 24 ? 0 : get('hour')
    const asUtc = Date.UTC(get('year'), get('month') - 1, get('day'), h, get('minute'), get('second'))
    return Math.round((asUtc - utcMs) / 60000)
  } catch {
    return 0
  }
}

function zonedMidnightMillis(iso: string, timeZoneId: string): number {
  const [y, m, d] = parseIsoParts(iso)
  const utcGuess = Date.UTC(y, m - 1, d, 0, 0, 0, 0)
  const off1 = getZoneOffsetMinutes(utcGuess, timeZoneId)
  const cand = utcGuess - off1 * 60000
  const off2 = getZoneOffsetMinutes(cand, timeZoneId)
  return utcGuess - off2 * 60000
}

function getZonedDateParts(epochMs: number, timeZoneId: string): {
  year: number
  month: number
  day: number
  hour: number
  minute: number
  weekday: string
} {
  const fixed = parseFixedGmtOffsetMinutes(timeZoneId)
  if (fixed !== null) {
    const shifted = new Date(epochMs + fixed * 60000)
    const weekdays = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']
    return {
      year: shifted.getUTCFullYear(),
      month: shifted.getUTCMonth() + 1,
      day: shifted.getUTCDate(),
      hour: shifted.getUTCHours(),
      minute: shifted.getUTCMinutes(),
      weekday: weekdays[shifted.getUTCDay()],
    }
  }
  try {
    const dtf = new Intl.DateTimeFormat('en-US', {
      timeZone: timeZoneId,
      hour12: false,
      weekday: 'short',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    })
    const parts = dtf.formatToParts(new Date(epochMs))
    const get = (t: string) => parts.find((p) => p.type === t)?.value ?? '0'
    const hourNum = Number(get('hour'))
    return {
      year: Number(get('year')),
      month: Number(get('month')),
      day: Number(get('day')),
      hour: hourNum === 24 ? 0 : hourNum,
      minute: Number(get('minute')),
      weekday: get('weekday'),
    }
  } catch {
    const dt = new Date(epochMs)
    const weekdays = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']
    return {
      year: dt.getFullYear(),
      month: dt.getMonth() + 1,
      day: dt.getDate(),
      hour: dt.getHours(),
      minute: dt.getMinutes(),
      weekday: weekdays[dt.getDay()],
    }
  }
}

function formatZonedDate(epochMs: number, timeZoneId: string): string {
  const p = getZonedDateParts(epochMs, timeZoneId)
  return `${String(p.month).padStart(2, '0')}/${String(p.day).padStart(2, '0')}/${p.year}`
}

function formatZonedTime(epochMs: number, timeZoneId: string): string {
  const p = getZonedDateParts(epochMs, timeZoneId)
  const h12 = p.hour % 12 === 0 ? 12 : p.hour % 12
  const ampm = p.hour >= 12 ? 'PM' : 'AM'
  return `${h12}:${String(p.minute).padStart(2, '0')} ${ampm}`
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

export function ReportAdHocPage() {
  const { layout } = useLayout()
  const [searchParams, setSearchParams] = useSearchParams()

  const defaultTimeZone = useMemo(() => {
    try {
      return Intl.DateTimeFormat().resolvedOptions().timeZone || layout.defaultTimeZoneId || 'UTC'
    } catch {
      return layout.defaultTimeZoneId || 'UTC'
    }
  }, [layout.defaultTimeZoneId])

  const initialDefaults = useMemo(() => {
    const paramFrom = paramDateToIso(searchParams.get('from-date'))
    const paramTo = paramDateToIso(searchParams.get('to-date'))
    const fromIso = paramFrom && paramTo ? paramFrom : toIsoDateLocal(new Date(Date.now() - 4 * 3600_000))
    const toIso = paramFrom && paramTo ? paramTo : toIsoDateLocal(new Date())
    const rollupRaw = searchParams.get('rollup') || 'daily'
    const rollup: AppliedReport['rollup'] =
      rollupRaw === 'hourly' || rollupRaw === 'daily' || rollupRaw === 'weekly' || rollupRaw === 'monthly'
        ? rollupRaw
        : 'daily'
    return {
      agentRollupIds: layout.central ? searchParams.getAll('agent-rollup-id').filter(Boolean) : [''],
      metric: searchParams.get('metric') || '',
      transactionType: searchParams.get('transaction-type') || '',
      transactionName: searchParams.get('transaction-name') || '',
      timerName: searchParams.get('timer-name') || '',
      percentile: searchParams.get('percentile') || '',
      fromIso,
      toIso,
      rollup,
      timeZoneId: searchParams.get('time-zone-id') || defaultTimeZone,
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const [fromIso, setFromIso] = useState(initialDefaults.fromIso)
  const [toIso, setToIso] = useState(initialDefaults.toIso)
  const [selectedAgentIds, setSelectedAgentIds] = useState<string[]>(initialDefaults.agentRollupIds)
  const [metric, setMetric] = useState(initialDefaults.metric)
  const [transactionType, setTransactionType] = useState(initialDefaults.transactionType)
  const [transactionName, setTransactionName] = useState(initialDefaults.transactionName)
  const [timerName, setTimerName] = useState(initialDefaults.timerName)
  const [percentile, setPercentile] = useState(initialDefaults.percentile)
  const [rollup, setRollup] = useState<'hourly' | 'daily' | 'weekly' | 'monthly'>(
    initialDefaults.rollup
  )
  const [timeZoneId, setTimeZoneId] = useState(initialDefaults.timeZoneId)

  const [allAgentRollups, setAllAgentRollups] = useState<ReportAgentRollup[]>([])
  const [transactionTypes, setTransactionTypes] = useState<string[]>([])
  const [gauges, setGauges] = useState<GaugeInfo[]>([])
  const [timerNames, setTimerNames] = useState<string[]>([])

  const [validationError, setValidationError] = useState<string | null>(null)
  const [httpError, setHttpError] = useState<unknown>(null)
  const [running, setRunning] = useState(false)

  const [appliedReport, setAppliedReport] = useState<AppliedReport | null>(null)
  const [reportData, setReportData] = useState<ReportResponse | null>(null)
  const [sortAttr, setSortAttr] = useState<number | 'overall' | null>(null)
  const [sortAsc, setSortAsc] = useState(false)

  const gaugeUnits = useMemo(() => {
    const units: Record<string, string> = {}
    for (const g of gauges) {
      units[g.name] = g.unit ? ` ${g.unit}` : ''
    }
    return units
  }, [gauges])

  // Clear validation error on any form change
  useEffect(() => {
    setValidationError(null)
  }, [
    fromIso,
    toIso,
    selectedAgentIds,
    metric,
    transactionType,
    transactionName,
    timerName,
    percentile,
    rollup,
    timeZoneId,
  ])

  // 4-hour aggregate retention check
  const rollupExp2 = layout.rollupExpirationMillis[2] ?? 0
  const useFourHourAggregates = useMemo(() => {
    return isoToLocalEpochMs(fromIso) < Date.now() - rollupExp2
  }, [fromIso, rollupExp2])

  const availableRollups = useMemo(
    () => (useFourHourAggregates ? ['Daily', 'Weekly', 'Monthly'] : ['Hourly', 'Daily', 'Weekly', 'Monthly']),
    [useFourHourAggregates]
  )

  const availableTimeZones = useMemo(
    () => (useFourHourAggregates ? FOUR_HOUR_TIME_ZONES : layout.timeZoneIds),
    [useFourHourAggregates, layout.timeZoneIds]
  )

  useEffect(() => {
    if (useFourHourAggregates && rollup === 'hourly') {
      setRollup('daily')
    }
    if (useFourHourAggregates && !FOUR_HOUR_TIME_ZONES.includes(timeZoneId)) {
      const offsetMins = getZoneOffsetMinutes(Date.now(), timeZoneId)
      const nearest = 4 * Math.ceil(Math.floor(offsetMins / 60) / 4)
      if (nearest === 0) setTimeZoneId('GMT')
      else if (nearest > 0) setTimeZoneId(`GMT+${nearest}:00`)
      else setTimeZoneId(`GMT${nearest}:00`)
    } else if (!useFourHourAggregates && layout.timeZoneIds.length > 0 && !layout.timeZoneIds.includes(timeZoneId)) {
      setTimeZoneId(layout.timeZoneIds.includes(defaultTimeZone) ? defaultTimeZone : layout.timeZoneIds[0])
    }
  }, [useFourHourAggregates, rollup, timeZoneId, layout.timeZoneIds, defaultTimeZone])

  // Fetch agent rollups (central mode only)
  useEffect(() => {
    if (!layout.central || fromIso > toIso) return
    let cancelled = false
    apiGet<ReportAgentRollup[]>('/backend/report/agent-rollups', {
      'from-date': isoToCompact(fromIso),
      'to-date': isoToCompact(toIso),
      'time-zone-id': timeZoneId,
    })
      .then((list) => {
        if (!cancelled) setAllAgentRollups(list || [])
      })
      .catch((err) => {
        if (!cancelled) setHttpError(err)
      })
    return () => {
      cancelled = true
    }
  }, [layout.central, fromIso, toIso, timeZoneId])

  // Fetch transaction types and gauges
  useEffect(() => {
    if (fromIso > toIso) return
    if (layout.central && selectedAgentIds.length === 0) {
      setGauges([])
      setTransactionTypes([])
      return
    }
    let cancelled = false
    apiGet<TransactionTypesAndGaugesResponse>('/backend/report/transaction-types-and-gauges', {
      'agent-rollup-ids': layout.central ? selectedAgentIds : [''],
      'from-date': isoToCompact(fromIso),
      'to-date': isoToCompact(toIso),
      'time-zone-id': timeZoneId,
    })
      .then((resp) => {
        if (cancelled) return
        const tt = resp.transactionTypes || []
        setTransactionTypes(tt)
        setTransactionType((prev) => (prev && !tt.includes(prev) ? '' : prev))
        setGauges(resp.gauges || [])
      })
      .catch((err) => {
        if (!cancelled) setHttpError(err)
      })
    return () => {
      cancelled = true
    }
  }, [layout.central, selectedAgentIds, fromIso, toIso, timeZoneId])

  // Fetch timer names for breakdown timer metrics
  useEffect(() => {
    if (!showTimerName(metric) || !transactionType || fromIso > toIso) {
      setTimerNames([])
      return
    }
    if (layout.central && selectedAgentIds.length === 0) {
      setTimerNames([])
      return
    }
    let cancelled = false
    apiGet<string[]>('/backend/report/timer-names', {
      'agent-rollup-ids': layout.central ? selectedAgentIds : [''],
      'transaction-type': transactionType,
      'transaction-name': transactionName || '',
      'from-date': isoToCompact(fromIso),
      'to-date': isoToCompact(toIso),
      'time-zone-id': timeZoneId,
    })
      .then((names) => {
        if (cancelled) return
        const list = names || []
        setTimerNames(list)
        setTimerName((prev) => (prev && !list.includes(prev) ? '' : prev))
      })
      .catch(() => {
        if (!cancelled) {
          setTimerNames([])
          setTimerName('')
        }
      })
    return () => {
      cancelled = true
    }
  }, [layout.central, selectedAgentIds, metric, transactionType, transactionName, fromIso, toIso, timeZoneId])

  const metrics = useMemo<MetricOption[]>(() => {
    const list = [...BASE_METRICS]
    if (layout.central && selectedAgentIds.length === 0) {
      list.push({
        id: 'gauge:select',
        display: '(select one or more agents to see available gauges)',
        disabled: true,
      })
    } else {
      for (const g of gauges) {
        list.push({ id: `gauge:${g.name}`, display: g.display })
      }
    }
    return list
  }, [layout.central, selectedAgentIds.length, gauges])

  function handleMetricChange(nextMetric: string) {
    setMetric(nextMetric)
    if (!showTransactionTypeAndName(nextMetric)) {
      setTransactionType('')
      setTransactionName('')
    }
    if (nextMetric !== 'transaction:x-percentile') {
      setPercentile('')
    }
    if (!showTimerName(nextMetric)) {
      setTimerName('')
      setTimerNames([])
    }
  }

  function validateForm(): string | null {
    if (layout.central && selectedAgentIds.length === 0) {
      return 'Select one or more agents'
    }
    if (!metric) {
      return 'Metric is required'
    }
    if (showTransactionTypeAndName(metric) && !transactionType) {
      return 'Transaction type is required'
    }
    if (showTimerName(metric) && !timerName) {
      return 'Enter a timer name'
    }
    if (transactionNameRequired(metric) && !transactionName) {
      return 'Enter a transaction name'
    }
    if (metric === 'transaction:x-percentile') {
      const p = Number(percentile)
      if (!percentile || !Number.isFinite(p) || p <= 0 || p >= 100) {
        return 'Percentile is required'
      }
    }
    if (!fromIso || !toIso || fromIso > toIso) {
      return 'From date must be before or equal to To date'
    }
    const [fy, fm, fd] = parseIsoParts(fromIso)
    const [ty, tm, td] = parseIsoParts(toIso)
    const fromUtc = Date.UTC(fy, fm - 1, fd)
    const toUtc = Date.UTC(ty, tm - 1, td)
    if (rollup === 'weekly' && (toUtc - fromUtc) / 86400000 < 6) {
      return 'Must select at least one full week when using weekly rollup'
    }
    if (rollup === 'monthly') {
      if (fd !== 1) {
        return 'From date must be the first day of a month when using monthly rollup'
      }
      const lastDayUtc = Date.UTC(fy, fm, 0)
      if (toUtc < lastDayUtc) {
        return 'Must select at least one full month when using month rollup'
      }
    }
    return null
  }

  async function handleRunReport(e: React.FormEvent) {
    e.preventDefault()
    const errMsg = validateForm()
    if (errMsg) {
      setValidationError(errMsg)
      return
    }
    setValidationError(null)
    setHttpError(null)

    const currentApplied: AppliedReport = {
      agentRollupIds: layout.central ? [...selectedAgentIds] : [''],
      metric,
      transactionType: showTransactionTypeAndName(metric) ? transactionType : '',
      transactionName: showTransactionTypeAndName(metric) ? transactionName : '',
      timerName: showTimerName(metric) ? timerName : '',
      percentile: metric === 'transaction:x-percentile' ? percentile : '',
      fromIso,
      toIso,
      rollup,
      timeZoneId,
    }

    setSearchParams((prev) => {
      const next = new URLSearchParams(prev)
      next.delete('agent-rollup-id')
      if (layout.central) {
        for (const id of currentApplied.agentRollupIds) {
          next.append('agent-rollup-id', id)
        }
      }
      next.set('metric', currentApplied.metric)
      if (currentApplied.transactionType) next.set('transaction-type', currentApplied.transactionType)
      else next.delete('transaction-type')
      if (currentApplied.transactionName) next.set('transaction-name', currentApplied.transactionName)
      else next.delete('transaction-name')
      if (currentApplied.timerName) next.set('timer-name', currentApplied.timerName)
      else next.delete('timer-name')
      if (currentApplied.percentile) next.set('percentile', currentApplied.percentile)
      else next.delete('percentile')
      next.set('from-date', isoToCompact(currentApplied.fromIso))
      next.set('to-date', isoToCompact(currentApplied.toIso))
      if (currentApplied.rollup === 'daily') next.delete('rollup')
      else next.set('rollup', currentApplied.rollup)
      next.set('time-zone-id', currentApplied.timeZoneId)
      return next
    })

    setRunning(true)
    try {
      const resp = await apiGet<ReportResponse>('/backend/report', {
        'agent-rollup-ids': currentApplied.agentRollupIds,
        metric: currentApplied.metric,
        'transaction-type': currentApplied.transactionType || undefined,
        'transaction-name': showTransactionTypeAndName(currentApplied.metric)
          ? currentApplied.transactionName
          : undefined,
        'timer-name': currentApplied.timerName || undefined,
        percentile: currentApplied.percentile ? Number(currentApplied.percentile) : undefined,
        'from-date': isoToCompact(currentApplied.fromIso),
        'to-date': isoToCompact(currentApplied.toIso),
        rollup: currentApplied.rollup.toUpperCase(),
        'time-zone-id': currentApplied.timeZoneId,
      })
      setAppliedReport(currentApplied)
      setReportData(resp)
      setSortAttr(null)
      setSortAsc(false)
    } catch (err) {
      setHttpError(err)
    } finally {
      setRunning(false)
    }
  }

  const colorPoolRef = useRef(createKeyedColorPool())

  const formatMetricValue = useCallback(
    (value: number, m: string) => {
      if (m.startsWith('gauge:')) return formatGaugeValue(value)
      if (m === 'transaction:count' || m === 'error:count') return formatCount(value)
      if (m === 'error:rate') return `${formatMillis(value)} %`
      return formatMillis(value)
    },
    []
  )

  const getFromTo = useCallback(
    (xval: number, rep: AppliedReport): [number, number] => {
      if (rep.rollup === 'hourly') {
        const from = xval - 30 * 60 * 1000
        return [from, from + 3600_000]
      }
      if (rep.rollup === 'daily') {
        const p = getZonedDateParts(xval, rep.timeZoneId)
        const iso = `${p.year}-${String(p.month).padStart(2, '0')}-${String(p.day).padStart(2, '0')}`
        const from = zonedMidnightMillis(iso, rep.timeZoneId)
        const to = zonedMidnightMillis(addDaysIso(iso, 1), rep.timeZoneId)
        return [from, to]
      }
      if (rep.rollup === 'weekly') {
        const p = getZonedDateParts(xval, rep.timeZoneId)
        const iso = `${p.year}-${String(p.month).padStart(2, '0')}-${String(p.day).padStart(2, '0')}`
        const fromIsoWeek = addDaysIso(iso, -3)
        const from = zonedMidnightMillis(fromIsoWeek, rep.timeZoneId)
        const to = zonedMidnightMillis(addDaysIso(fromIsoWeek, 7), rep.timeZoneId)
        return [from, to]
      }
      // monthly
      const p = getZonedDateParts(xval, rep.timeZoneId)
      const fromIsoMonth = `${p.year}-${String(p.month).padStart(2, '0')}-01`
      const nextMonthYear = p.month === 12 ? p.year + 1 : p.year
      const nextMonth = p.month === 12 ? 1 : p.month + 1
      const toIsoMonth = `${nextMonthYear}-${String(nextMonth).padStart(2, '0')}-01`
      return [
        zonedMidnightMillis(fromIsoMonth, rep.timeZoneId),
        zonedMidnightMillis(toIsoMonth, rep.timeZoneId),
      ]
    },
    []
  )

  const computed = useMemo(() => {
    if (!appliedReport || !reportData) return null
    const rawSeries = reportData.dataSeries || []
    const chartNoData = rawSeries.every((s) => !s.data || s.data.length === 0)
    const xvalSet = new Set<number>()
    const yvalMaps: Record<string, Record<number, number>> = {}
    const labels: string[] = []

    for (const ds of rawSeries) {
      labels.push(ds.name)
      const map: Record<number, number> = {}
      for (const pt of ds.data) {
        if (pt != null) {
          xvalSet.add(pt[0])
          if (pt[1] != null) {
            map[pt[0]] = pt[1]
          }
        }
      }
      yvalMaps[ds.name] = map
    }

    const allXvals = Array.from(xvalSet).sort((a, b) => a - b)
    colorPoolRef.current.reset(labels)

    let yAxisLabel = ''
    let convertBytes = false
    if (
      appliedReport.metric === 'transaction:x-percentile' ||
      appliedReport.metric === 'transaction:average' ||
      appliedReport.metric === 'transaction:timer-inclusive' ||
      appliedReport.metric === 'transaction:timer-exclusive'
    ) {
      yAxisLabel = 'milliseconds'
    } else if (appliedReport.metric === 'error:rate') {
      yAxisLabel = 'percent'
    } else if (appliedReport.metric.startsWith('gauge:')) {
      const gName = appliedReport.metric.slice('gauge:'.length)
      const rawUnit = (gaugeUnits[gName] || '').trim()
      if (rawUnit === 'bytes') {
        yAxisLabel = 'MB'
        convertBytes = true
      } else {
        yAxisLabel = rawUnit
      }
    }

    const chartSeries: DataSeries[] = []
    const tableRows: TableRow[] = []

    appliedReport.agentRollupIds.forEach((id, idx) => {
      const ds = rawSeries[idx]
      if (!ds) return
      const map = yvalMaps[ds.name] || {}
      const values: Record<number, number> = {}
      for (const xv of allXvals) {
        values[xv] = map[xv] !== undefined ? map[xv] : Number.NEGATIVE_INFINITY
      }
      tableRows.push({
        label: ds.name,
        agentRollupId: id,
        overall: ds.overall !== undefined ? ds.overall : Number.NEGATIVE_INFINITY,
        values,
      })
      chartSeries.push({
        name: ds.name,
        shortLabel: ds.name,
        data: ds.data.map((pt) => {
          if (pt == null) return null
          return [
            pt[0],
            pt[1] == null ? null : convertBytes ? pt[1] / (1024 * 1024) : pt[1],
          ] as [number, number | null]
        }),
      })
    })

    const chartFrom = zonedMidnightMillis(appliedReport.fromIso, appliedReport.timeZoneId)
    const chartTo = zonedMidnightMillis(
      addDaysIso(appliedReport.toIso, 1),
      appliedReport.timeZoneId
    )
    const legendItems = chartSeries.map((s) => ({
      text: s.name,
      color: colorPoolRef.current.get(s.name),
    }))

    return {
      chartNoData,
      allXvals,
      yvalMaps,
      yAxisLabel,
      chartSeries,
      tableRows,
      chartFrom,
      chartTo,
      legendItems,
    }
  }, [appliedReport, reportData, gaugeUnits])

  const sortedRows = useMemo(() => {
    if (!computed) return []
    const rows = [...computed.tableRows]
    if (sortAttr === null) return rows
    rows.sort((a, b) => {
      const va = sortAttr === 'overall' ? a.overall : (a.values[sortAttr] ?? Number.NEGATIVE_INFINITY)
      const vb = sortAttr === 'overall' ? b.overall : (b.values[sortAttr] ?? Number.NEGATIVE_INFINITY)
      if (va !== vb) {
        return sortAsc ? va - vb : vb - va
      }
      return b.overall - a.overall
    })
    return rows
  }, [computed, sortAttr, sortAsc])

  function handleSort(attr: number | 'overall') {
    if (sortAttr === attr) {
      setSortAsc((prev) => !prev)
    } else {
      setSortAttr(attr)
      setSortAsc(false)
    }
  }

  function formatColumnHeader(xval: number, rep: AppliedReport): string {
    const [from, to] = getFromTo(xval, rep)
    if (rep.rollup === 'hourly') {
      return `${formatZonedDate(from, rep.timeZoneId)} ${formatZonedTime(from, rep.timeZoneId)} to ${formatZonedTime(to, rep.timeZoneId)}`
    }
    if (rep.rollup === 'daily') {
      const p = getZonedDateParts(from, rep.timeZoneId)
      return `${p.weekday} ${formatZonedDate(from, rep.timeZoneId)}`
    }
    if (rep.rollup === 'weekly') {
      const toDisplay = to - 86400000
      const p1 = getZonedDateParts(from, rep.timeZoneId)
      const p2 = getZonedDateParts(toDisplay, rep.timeZoneId)
      return `${p1.weekday} ${formatZonedDate(from, rep.timeZoneId)} through ${p2.weekday} ${formatZonedDate(toDisplay, rep.timeZoneId)}`
    }
    const toDisplay = to - 86400000
    return `${formatZonedDate(from, rep.timeZoneId)} through ${formatZonedDate(toDisplay, rep.timeZoneId)}`
  }

  function formatExportHeader(xval: number, rep: AppliedReport): string {
    const [from] = getFromTo(xval, rep)
    if (rep.rollup === 'hourly') {
      return `${formatZonedDate(from, rep.timeZoneId)} ${formatZonedTime(from, rep.timeZoneId)}`
    }
    return formatZonedDate(from, rep.timeZoneId)
  }

  function buildDrillDownHref(
    agentRollupId: string,
    fromMs: number,
    toMs: number,
    rep: AppliedReport
  ): string {
    let path = '/modern/transaction/average'
    if (rep.metric === 'transaction:x-percentile') path = '/modern/transaction/percentiles'
    else if (rep.metric === 'transaction:count') path = '/modern/transaction/throughput'
    else if (rep.metric === 'error:rate' || rep.metric === 'error:count') path = '/modern/error/messages'
    else if (rep.metric.startsWith('gauge:')) path = '/modern/jvm/gauges'

    const params = new URLSearchParams()
    if (layout.central && agentRollupId) {
      if (agentRollupId.endsWith('::')) params.set('agent-rollup-id', agentRollupId)
      else params.set('agent-id', agentRollupId)
    }
    if (showTransactionTypeAndName(rep.metric)) {
      params.set('transaction-type', rep.transactionType)
      if (rep.transactionName) params.set('transaction-name', rep.transactionName)
    } else if (rep.metric.startsWith('gauge:')) {
      params.set('gauge-name', rep.metric.slice('gauge:'.length))
    }
    params.set('from', String(fromMs))
    params.set('to', String(toMs))
    if (rep.metric === 'transaction:x-percentile' && rep.percentile) {
      params.set('percentile', rep.percentile)
    }
    return `${path}?${params.toString()}`
  }

  const tooltipContent = useCallback(
    (
      from: number,
      to: number | null,
      _dataIndex: number,
      seriesIndex: number,
      allSeries: PlotSeries[]
    ) => {
      if (!appliedReport || !computed) return ''
      const xval = to ?? from
      const [bucketFrom, bucketTo] = getFromTo(xval, appliedReport)
      let headerText: string
      if (appliedReport.rollup === 'hourly') {
        headerText = `${formatZonedTime(bucketFrom, appliedReport.timeZoneId)} to ${formatZonedTime(bucketTo, appliedReport.timeZoneId)}`
      } else if (appliedReport.rollup === 'daily') {
        const p = getZonedDateParts(bucketFrom, appliedReport.timeZoneId)
        headerText = `${p.weekday} ${formatZonedDate(bucketFrom, appliedReport.timeZoneId)}`
      } else if (appliedReport.rollup === 'weekly') {
        const toDay = bucketTo - 86400000
        const p1 = getZonedDateParts(bucketFrom, appliedReport.timeZoneId)
        const p2 = getZonedDateParts(toDay, appliedReport.timeZoneId)
        headerText = `${p1.weekday} ${formatZonedDate(bucketFrom, appliedReport.timeZoneId)} through ${p2.weekday} ${formatZonedDate(toDay, appliedReport.timeZoneId)}`
      } else {
        const toDay = bucketTo - 86400000
        headerText = `${formatZonedDate(bucketFrom, appliedReport.timeZoneId)} through ${formatZonedDate(toDay, appliedReport.timeZoneId)}`
      }

      let html = `<div style="font-weight:600;margin-bottom:2px">${escapeHtml(headerText)}</div>`
      html += '<table style="border-spacing:4px 1px">'
      allSeries.forEach((s, si) => {
        const rawVal = computed.yvalMaps[s.name]?.[xval]
        const text =
          rawVal === undefined
            ? 'no data'
            : `${formatMetricValue(rawVal, appliedReport.metric)}${computed.yAxisLabel ? ' ' + computed.yAxisLabel : ''}`
        html += `<tr${si === seriesIndex ? ' style="background:#eee"' : ''}>`
        html += `<td><span style="display:inline-block;width:10px;height:10px;background:${s.color};border:1px solid #ccc"></span></td>`
        html += `<td>${escapeHtml(s.name)}</td>`
        html += `<td style="font-weight:600">${escapeHtml(text)}</td>`
        html += '</tr>'
      })
      html += '</table>'
      return html
    },
    [appliedReport, computed, getFromTo, formatMetricValue]
  )

  function handleExportCsv() {
    if (!appliedReport || !computed) return
    let csv = '<strong>'
    if (layout.central) {
      csv += 'Agent,'
    }
    for (const xval of computed.allXvals) {
      csv += `${escapeHtml(formatExportHeader(xval, appliedReport))},`
    }
    csv += 'Overall</strong><br>'
    for (const row of sortedRows) {
      if (layout.central) {
        csv += `${escapeHtml(row.label)},`
      }
      for (const xval of computed.allXvals) {
        const val = row.values[xval]
        if (val !== Number.NEGATIVE_INFINITY) {
          csv += String(val)
        }
        csv += ','
      }
      csv += `${row.overall === Number.NEGATIVE_INFINITY ? '' : String(row.overall)}\n`
    }
    const csvWindow = window.open()
    if (csvWindow) {
      csvWindow.document.open()
      csvWindow.document.write(`<pre style="white-space: pre-wrap;">${csv}</pre>`)
      csvWindow.document.close()
    }
  }

  return (
    <div className="space-y-6">
      {httpError != null && <HttpError error={httpError} />}

      <form onSubmit={handleRunReport} className="space-y-4 max-w-3xl" noValidate>
        {/* From */}
        <div className="grid grid-cols-1 xl:grid-cols-12 gap-2 items-center">
          <label htmlFor="reportFromDate" className="xl:col-span-3 text-sm font-medium text-gray-700">
            From
          </label>
          <div className="xl:col-span-9">
            <input
              id="reportFromDate"
              type="date"
              value={fromIso}
              onChange={(e) => setFromIso(e.target.value)}
              className="rounded-md border border-gray-300 px-3 py-1.5 text-sm shadow-sm focus:border-blue-500 focus:outline-none"
            />
          </div>
        </div>

        {/* To */}
        <div className="grid grid-cols-1 xl:grid-cols-12 gap-2 items-center">
          <label htmlFor="reportToDate" className="xl:col-span-3 text-sm font-medium text-gray-700">
            To
          </label>
          <div className="xl:col-span-9">
            <input
              id="reportToDate"
              type="date"
              value={toIso}
              onChange={(e) => setToIso(e.target.value)}
              className="rounded-md border border-gray-300 px-3 py-1.5 text-sm shadow-sm focus:border-blue-500 focus:outline-none"
            />
          </div>
        </div>

        {/* Agents (Central mode only) */}
        {layout.central && (
          <div className="grid grid-cols-1 xl:grid-cols-12 gap-2 items-start">
            <label htmlFor="reportAgentRollupIdSelect" className="xl:col-span-3 pt-1.5 text-sm font-medium text-gray-700">
              Agents
            </label>
            <div className="xl:col-span-9 space-y-2 max-w-xl">
              {allAgentRollups.length === 0 ? (
                <div className="rounded-md border border-gray-200 bg-gray-50 px-3 py-2 text-sm text-gray-500">
                  No active agents in selected time period
                </div>
              ) : (
                <div id="reportAgentRollupIdSelect">
                  <GtSelectpicker
                    multiple
                    ariaLabel="Agents"
                    placeholder="Select agents"
                    values={selectedAgentIds}
                    onChangeMultiple={setSelectedAgentIds}
                    options={allAgentRollups.map((ar) => ({
                      value: ar.id,
                      label: ar.lastDisplayPart,
                      indent: ar.depth,
                    }))}
                    className="w-full"
                  />
                </div>
              )}
            </div>
          </div>
        )}

        {/* Metric */}
        <div className="grid grid-cols-1 xl:grid-cols-12 gap-2 items-center">
          <label htmlFor="reportMetric" className="xl:col-span-3 text-sm font-medium text-gray-700">
            Metric
          </label>
          <div className="xl:col-span-9">
            <select
              id="reportMetric"
              name="reportMetric"
              value={metric}
              onChange={(e) => handleMetricChange(e.target.value)}
              className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm shadow-sm focus:border-blue-500 focus:outline-none"
            >
              <option value="" disabled />
              {metrics.map((m) => (
                <option
                  key={m.id}
                  value={m.id}
                  disabled={m.disabled}
                  style={m.heading ? { fontWeight: 'bold' } : undefined}
                >
                  {m.display}
                </option>
              ))}
            </select>
          </div>
        </div>

        {/* Transaction type & name */}
        {showTransactionTypeAndName(metric) && (
          <>
            <div className="grid grid-cols-1 xl:grid-cols-12 gap-2 items-center">
              <label htmlFor="reportTransactionType" className="xl:col-span-3 text-sm font-medium text-gray-700">
                Transaction type
              </label>
              <div className="xl:col-span-9">
                <select
                  id="reportTransactionType"
                  name="reportTransactionType"
                  value={transactionType}
                  onChange={(e) => setTransactionType(e.target.value)}
                  className="w-full max-w-[25em] rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm shadow-sm focus:border-blue-500 focus:outline-none"
                >
                  {layout.central && selectedAgentIds.length === 0 ? (
                    <option value="__________" disabled>
                      (select one or more agents to see available transaction types)
                    </option>
                  ) : (
                    !transactionType && <option value="" disabled />
                  )}
                  {transactionTypes.map((tt) => (
                    <option key={tt} value={tt}>
                      {tt}
                    </option>
                  ))}
                </select>
              </div>
            </div>

            <div className="grid grid-cols-1 xl:grid-cols-12 gap-2 items-center">
              <label htmlFor="reportTransactionName" className="xl:col-span-3 text-sm font-medium text-gray-700">
                Transaction name
              </label>
              <div className="xl:col-span-9">
                <input
                  id="reportTransactionName"
                  type="text"
                  value={transactionName}
                  onChange={(e) => setTransactionName(e.target.value)}
                  className="w-full max-w-[20em] rounded-md border border-gray-300 px-3 py-1.5 text-sm shadow-sm focus:border-blue-500 focus:outline-none"
                />
              </div>
            </div>
          </>
        )}

        {/* Timer name */}
        {showTimerName(metric) && (
          <div className="grid grid-cols-1 xl:grid-cols-12 gap-2 items-start">
            <label htmlFor="reportTimerName" className="xl:col-span-3 pt-1.5 text-sm font-medium text-gray-700">
              Timer name
            </label>
            <div className="xl:col-span-9 space-y-1">
              <select
                id="reportTimerName"
                name="reportTimerName"
                value={timerName}
                onChange={(e) => setTimerName(e.target.value)}
                disabled={timerNames.length === 0}
                className="w-full max-w-[25em] rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm shadow-sm disabled:bg-gray-100 focus:border-blue-500 focus:outline-none"
              >
                {timerNames.length === 0 ? (
                  <option value="" disabled>
                    (no timers for selected type / name / date range)
                  </option>
                ) : (
                  !timerName && <option value="" disabled />
                )}
                {timerNames.map((tn) => (
                  <option key={tn} value={tn}>
                    {tn}
                  </option>
                ))}
              </select>
              {metric === 'transaction:timer-inclusive' && (
                <p className="text-xs text-gray-500">
                  Inclusive time is total time in the timer including nested children (same as the breakdown table).
                </p>
              )}
              {metric === 'transaction:timer-exclusive' && (
                <p className="text-xs text-gray-500">
                  Exclusive time is self time only (same as stacked response-time chart segments).
                </p>
              )}
            </div>
          </div>
        )}

        {/* Percentile */}
        {metric === 'transaction:x-percentile' && (
          <div className="grid grid-cols-1 xl:grid-cols-12 gap-2 items-center">
            <label htmlFor="reportPercentile" className="xl:col-span-3 text-sm font-medium text-gray-700">
              Percentile
            </label>
            <div className="xl:col-span-9 flex items-center gap-2">
              <input
                id="reportPercentile"
                type="text"
                value={percentile}
                onChange={(e) => setPercentile(e.target.value)}
                className="w-[7em] rounded-md border border-gray-300 px-3 py-1.5 text-sm shadow-sm focus:border-blue-500 focus:outline-none"
              />
              <span className="text-sm text-gray-600">percentile</span>
            </div>
          </div>
        )}

        {/* Rollup */}
        <div className="grid grid-cols-1 xl:grid-cols-12 gap-2 items-center">
          <label htmlFor="reportRollup" className="xl:col-span-3 text-sm font-medium text-gray-700">
            Rollup
          </label>
          <div className="xl:col-span-9">
            <select
              id="reportRollup"
              value={rollup}
              onChange={(e) => setRollup(e.target.value as AppliedReport['rollup'])}
              className="w-full max-w-[10em] rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm shadow-sm focus:border-blue-500 focus:outline-none"
            >
              {availableRollups.map((label) => (
                <option key={label} value={label.toLowerCase()}>
                  {label}
                </option>
              ))}
            </select>
          </div>
        </div>

        {/* Time zone */}
        <div className="grid grid-cols-1 xl:grid-cols-12 gap-2 items-start">
          <label htmlFor="reportTimeZone" className="xl:col-span-3 pt-1.5 text-sm font-medium text-gray-700">
            Time zone
          </label>
          <div className="xl:col-span-9 space-y-1">
            <select
              id="reportTimeZone"
              value={timeZoneId}
              onChange={(e) => setTimeZoneId(e.target.value)}
              className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm shadow-sm focus:border-blue-500 focus:outline-none"
            >
              {availableTimeZones.map((tz) => (
                <option key={tz} value={tz}>
                  {tz}
                </option>
              ))}
            </select>
            {useFourHourAggregates && (
              <p className="text-xs text-gray-600">
                Time zone selection is limited because the time range exceeds the configured retention for 30-minute
                interval data and so 4-hour interval data must be used instead.
              </p>
            )}
          </div>
        </div>

        {/* Submit */}
        <div className="grid grid-cols-1 xl:grid-cols-12 gap-2 items-center pt-2">
          <div className="xl:col-start-4 xl:col-span-9 flex items-center gap-3">
            <Button type="submit" disabled={running}>
              {running ? 'Running...' : 'Run report'}
            </Button>
            {validationError && (
              <span className="text-sm font-medium text-red-600">{validationError}</span>
            )}
          </div>
        </div>
      </form>

      {/* Chart + Table + CSV Export */}
      {computed && appliedReport && (
        <div className="space-y-6 pt-2">
          <div className="rounded-lg border bg-white p-4 relative">
            {computed.chartNoData ? (
              <div className="flex items-center justify-center h-[300px] text-sm text-gray-500">
                No data for this time period
              </div>
            ) : (
              <>
                <TimeSeriesChart
                  dataSeries={computed.chartSeries}
                  chartFrom={computed.chartFrom}
                  chartTo={computed.chartTo}
                  dataPointIntervalMillis={0}
                  colorPool={colorPoolRef.current}
                  stacked={false}
                  showPoints={true}
                  yAxisLabel={computed.yAxisLabel}
                  tooltipContent={tooltipContent}
                />
                {layout.central && computed.legendItems.length > 0 && (
                  <div className="mt-3 flex flex-wrap justify-center gap-3 text-xs">
                    {computed.legendItems.map((item) => (
                      <div key={item.text} className="flex items-center gap-1.5">
                        <span
                          className="inline-block w-3 h-3 border border-gray-300"
                          style={{ backgroundColor: item.color }}
                        />
                        <span>{item.text}</span>
                      </div>
                    ))}
                  </div>
                )}
              </>
            )}
          </div>

          {!computed.chartNoData && (
            <>
              <div className="overflow-x-auto">
                <table className="w-full text-sm border-collapse">
                  <thead>
                    <tr className="border-b text-gray-700">
                      {layout.central && <th scope="col" className="py-2 pr-4 text-left" />}
                      {computed.allXvals.map((xval) => (
                        <th key={xval} scope="col" className="py-2 px-3 text-right font-semibold">
                          {layout.central ? (
                            <button
                              type="button"
                              onClick={() => handleSort(xval)}
                              className="inline-flex items-center gap-1 hover:underline"
                            >
                              {sortAttr === xval &&
                                (sortAsc ? (
                                  <ArrowUp className="h-3.5 w-3.5" />
                                ) : (
                                  <ArrowDown className="h-3.5 w-3.5" />
                                ))}
                              <span>{formatColumnHeader(xval, appliedReport)}</span>
                            </button>
                          ) : (
                            <span>{formatColumnHeader(xval, appliedReport)}</span>
                          )}
                        </th>
                      ))}
                      <th scope="col" className="py-2 pl-3 text-right font-semibold whitespace-nowrap">
                        {layout.central ? (
                          <button
                            type="button"
                            onClick={() => handleSort('overall')}
                            className="inline-flex items-center gap-1 hover:underline"
                          >
                            {sortAttr === 'overall' &&
                              (sortAsc ? (
                                <ArrowUp className="h-3.5 w-3.5" />
                              ) : (
                                <ArrowDown className="h-3.5 w-3.5" />
                              ))}
                            <span>Overall</span>
                          </button>
                        ) : (
                          <span>Overall</span>
                        )}
                      </th>
                    </tr>
                  </thead>
                  <tbody className="divide-y">
                    {sortedRows.map((row) => {
                      const overallFrom = zonedMidnightMillis(
                        appliedReport.fromIso,
                        appliedReport.timeZoneId
                      )
                      const overallTo = zonedMidnightMillis(
                        addDaysIso(appliedReport.toIso, 1),
                        appliedReport.timeZoneId
                      )
                      return (
                        <tr key={`${row.agentRollupId}-${row.label}`}>
                          {layout.central && (
                            <th scope="row" className="py-2 pr-4 text-left font-semibold">
                              {row.label}
                            </th>
                          )}
                          {computed.allXvals.map((xval) => {
                            const val = row.values[xval]
                            const [fromMs, toMs] = getFromTo(xval, appliedReport)
                            return (
                              <td key={xval} className="py-2 px-3 text-right tabular-nums">
                                {val === Number.NEGATIVE_INFINITY ? (
                                  <span>-</span>
                                ) : (
                                  <Link
                                    to={buildDrillDownHref(
                                      row.agentRollupId,
                                      fromMs,
                                      toMs,
                                      appliedReport
                                    )}
                                    className="text-blue-600 hover:underline"
                                  >
                                    {formatMetricValue(val, appliedReport.metric)}
                                  </Link>
                                )}
                              </td>
                            )
                          })}
                          <td className="py-2 pl-3 text-right tabular-nums">
                            {row.overall === Number.NEGATIVE_INFINITY ? (
                              <span>-</span>
                            ) : (
                              <Link
                                to={buildDrillDownHref(
                                  row.agentRollupId,
                                  overallFrom,
                                  overallTo,
                                  appliedReport
                                )}
                                className="text-blue-600 hover:underline"
                              >
                                {formatMetricValue(row.overall, appliedReport.metric)}
                              </Link>
                            )}
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>

              <div>
                <Button type="button" variant="secondary" onClick={handleExportCsv}>
                  Export as CSV
                </Button>
              </div>
            </>
          )}
        </div>
      )}
    </div>
  )
}
