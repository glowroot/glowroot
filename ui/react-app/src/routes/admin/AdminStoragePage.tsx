import { useCallback, useEffect, useRef, useState } from 'react'
import { useLocation } from 'react-router-dom'
import { useLayout } from '../../contexts/LayoutContext'
import { apiGet, apiPost } from '../../lib/api'
import { formatBytes } from '../../lib/formatting'
import { PATTERNS } from '../../lib/validation'
import { GtFormGroup } from '../../components/shared/GtFormGroup'
import { GtButtonGroup } from '../../components/shared/GtButtonGroup'
import { Input } from '../../components/ui/input'
import { Button } from '../../components/ui/button'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'

interface StorageConfig {
  rollupExpirationHours: number[]
  rollupCappedDatabaseSizesMb?: number[]
  traceCappedDatabaseSizeMb?: number
  traceExpirationHours?: number
  fullQueryTextExpirationHours?: number
  queryAndServiceCallRollupExpirationHours?: number[]
  profileRollupExpirationHours?: number[]
  h2CacheMode?: 'auto' | 'fixed' | 'percent'
  h2CacheValue?: number
  effectiveH2CacheMb?: number
  maxHeapMb?: number
  h2CacheSystemPropertyOverride?: boolean
  version?: string
}

interface H2TableAnalysis {
  name: string
  bytes: number
  rows: number
}

interface H2DiskSpaceAnalysis {
  h2DataFileSize?: number
  liveBytes?: number
  reclaimableBytes?: number
  tables?: H2TableAnalysis[]
}

interface TraceOverallCount {
  transactionType: string
  count: number
  errorCount: number
}

interface TraceNameCount {
  transactionType: string
  transactionName: string
  count: number
  errorCount: number
}

interface TraceCountAnalysis {
  overallCounts?: TraceOverallCount[]
  counts?: TraceNameCount[]
}

type NumInput = number | ''

function parseNumInput(raw: string): NumInput {
  if (raw === '') return ''
  const v = parseFloat(raw)
  return Number.isNaN(v) ? '' : v
}

function toNum(v: NumInput): number {
  return v === '' ? 0 : v
}

export function AdminStoragePage() {
  const { layout } = useLayout()
  const location = useLocation()
  const [config, setConfig] = useState<StorageConfig | null>(null)
  const [originalConfig, setOriginalConfig] = useState<StorageConfig | null>(null)
  // UI state in days (API uses hours)
  const [rollupDays, setRollupDays] = useState<NumInput[]>([])
  const [traceDays, setTraceDays] = useState<NumInput>(0)
  const [fullQueryDays, setFullQueryDays] = useState<NumInput>(0)
  const [queryRollupDays, setQueryRollupDays] = useState<NumInput[]>([])
  const [profileRollupDays, setProfileRollupDays] = useState<NumInput[]>([])
  const [rollupCappedSizes, setRollupCappedSizes] = useState<NumInput[]>([])
  const [traceCappedSize, setTraceCappedSize] = useState<NumInput>(0)
  const [h2CacheValue, setH2CacheValue] = useState<NumInput>(32)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)
  const [actionMessage, setActionMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null)
  const [actionLoading, setActionLoading] = useState<string | null>(null)
  const [h2Analysis, setH2Analysis] = useState<H2DiskSpaceAnalysis | null>(null)
  const [traceCountAnalysis, setTraceCountAnalysis] = useState<TraceCountAnalysis | null>(null)
  const firstRollupCappedRef = useRef<HTMLInputElement>(null)
  const traceCappedRef = useRef<HTMLInputElement>(null)

  const onNewData = useCallback((c: StorageConfig) => {
    const normalized: StorageConfig = { ...c }
    if (!layout.central) {
      if (!normalized.h2CacheMode) {
        normalized.h2CacheMode = 'fixed'
      }
      if (normalized.h2CacheValue === undefined || normalized.h2CacheValue === null) {
        normalized.h2CacheValue = 32
      }
    }
    setConfig(normalized)
    setOriginalConfig({ ...normalized })
    setRollupDays(normalized.rollupExpirationHours.map((h) => h / 24))
    setTraceDays((normalized.traceExpirationHours ?? 0) / 24)
    setFullQueryDays((normalized.fullQueryTextExpirationHours ?? 0) / 24)
    setQueryRollupDays((normalized.queryAndServiceCallRollupExpirationHours ?? []).map((h) => h / 24))
    setProfileRollupDays((normalized.profileRollupExpirationHours ?? []).map((h) => h / 24))
    setRollupCappedSizes([...(normalized.rollupCappedDatabaseSizesMb ?? [])])
    setTraceCappedSize(normalized.traceCappedDatabaseSizeMb ?? 0)
    setH2CacheValue(normalized.h2CacheValue ?? 32)
  }, [layout.central])

  const load = useCallback(async () => {
    try {
      const resp = await apiGet<StorageConfig>('/backend/admin/storage')
      onNewData(resp)
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
    }
  }, [onNewData])

  useEffect(() => { load() }, [load])

  useEffect(() => {
    if (loading || !config) return
    const timer = setTimeout(() => {
      if (location.hash === '#rollup-capped-database-size') {
        firstRollupCappedRef.current?.focus()
      } else if (location.hash === '#trace-capped-database-size') {
        traceCappedRef.current?.focus()
      }
    }, 0)
    return () => clearTimeout(timer)
  }, [loading, config, location.hash])

  function buildConfigFromUi(): StorageConfig {
    const result: StorageConfig = {
      ...config!,
      rollupExpirationHours: rollupDays.map((d) => toNum(d) * 24),
      traceExpirationHours: toNum(traceDays) * 24,
    }
    if (layout.central) {
      result.queryAndServiceCallRollupExpirationHours = queryRollupDays.map((d) => toNum(d) * 24)
      result.profileRollupExpirationHours = profileRollupDays.map((d) => toNum(d) * 24)
    } else {
      result.fullQueryTextExpirationHours = toNum(fullQueryDays) * 24
      if (config?.rollupCappedDatabaseSizesMb) {
        result.rollupCappedDatabaseSizesMb = rollupCappedSizes.map(toNum)
        result.traceCappedDatabaseSizeMb = toNum(traceCappedSize)
      }
      result.h2CacheMode = config?.h2CacheMode ?? 'fixed'
      result.h2CacheValue = toNum(h2CacheValue)
    }
    return result
  }

  const hasEmptyField =
    rollupDays.some((d) => d === '') ||
    traceDays === '' ||
    (layout.central
      ? queryRollupDays.some((d) => d === '') || profileRollupDays.some((d) => d === '')
      : fullQueryDays === '' ||
        rollupCappedSizes.some((s) => s === '') ||
        traceCappedSize === '' ||
        (config?.h2CacheMode !== 'auto' && h2CacheValue === ''))

  const hasChanges = config
    ? hasEmptyField || JSON.stringify(buildConfigFromUi()) !== JSON.stringify(originalConfig)
    : false

  async function handleSave() {
    if (hasEmptyField) {
      throw new Error('All numeric storage fields are required')
    }
    setH2Analysis(null)
    setTraceCountAnalysis(null)
    const updated = buildConfigFromUi()
    const resp = await apiPost<StorageConfig>('/backend/admin/storage', updated)
    onNewData(resp)
  }

  async function runAction(endpoint: string, confirmMsg: string) {
    if (!window.confirm(confirmMsg)) return
    setActionLoading(endpoint)
    setActionMessage(null)
    setH2Analysis(null)
    setTraceCountAnalysis(null)
    try {
      const resp = await apiPost<unknown>(
        endpoint,
        endpoint === '/backend/admin/update-cassandra-twcs-window-sizes' ? undefined : {},
      )
      if (endpoint === '/backend/admin/update-cassandra-twcs-window-sizes') {
        const updatedTableCount = typeof resp === 'number' ? resp : Number(resp)
        setActionMessage({
          type: 'success',
          text: `Updated ${updatedTableCount} table${updatedTableCount === 1 ? '' : 's'}`,
        })
      } else if (endpoint === '/backend/admin/analyze-h2-disk-space') {
        setH2Analysis(resp as H2DiskSpaceAnalysis)
        setActionMessage({ type: 'success', text: 'Analyzed' })
      } else if (endpoint === '/backend/admin/analyze-trace-counts') {
        setTraceCountAnalysis(resp as TraceCountAnalysis)
        setActionMessage({ type: 'success', text: 'Analyzed' })
      } else if (endpoint === '/backend/admin/defrag-h2-data') {
        setActionMessage({ type: 'success', text: 'Defragmented' })
      } else if (endpoint === '/backend/admin/compact-h2-data') {
        setActionMessage({ type: 'success', text: 'Compacted' })
      } else if (endpoint === '/backend/admin/delete-all-stored-data') {
        setActionMessage({ type: 'success', text: 'Deleted' })
      } else {
        setActionMessage({ type: 'success', text: 'Completed' })
      }
    } catch (e) {
      setActionMessage({ type: 'error', text: e instanceof Error ? e.message : 'Failed' })
    } finally {
      setActionLoading(null)
    }
  }

  function updateRollupDay(index: number, value: NumInput) {
    const updated = [...rollupDays]
    updated[index] = value
    setRollupDays(updated)
  }

  function updateCappedSize(index: number, value: NumInput) {
    const updated = [...rollupCappedSizes]
    updated[index] = value
    setRollupCappedSizes(updated)
  }

  function updateQueryRollupDay(index: number, value: NumInput) {
    const updated = [...queryRollupDays]
    updated[index] = value
    setQueryRollupDays(updated)
  }

  function updateProfileRollupDay(index: number, value: NumInput) {
    const updated = [...profileRollupDays]
    updated[index] = value
    setProfileRollupDays(updated)
  }

  if (loading) return <PageSpinner />
  if (error) return <HttpError error={error} />
  if (!config) return null

  const rollupLabels = ['1-minute', '5-minute', '30-minute', '4-hour']

  const h2CacheWarning = (() => {
    if (!config || layout.central || config.h2CacheSystemPropertyOverride) return ''
    const mode = config.h2CacheMode ?? 'fixed'
    const val = typeof h2CacheValue === 'number' ? h2CacheValue : 0
    const maxHeapMb = config.maxHeapMb ?? 0
    let targetMb: number
    if (mode === 'percent') {
      targetMb = Math.floor((maxHeapMb * val) / 100)
    } else if (mode === 'fixed') {
      targetMb = val
    } else {
      return ''
    }
    if (targetMb >= 256 || targetMb > (config.effectiveH2CacheMb ?? 0)) {
      return `Requested size will be clamped for shared-JVM safety (effective ${config.effectiveH2CacheMb ?? 0} MB). On very large H2 files, prefer shorter retention and Compact over a huge cache.`
    }
    return ''
  })()

  return (
    <div className="space-y-6">
      {/* Aggregate data */}
      <div className="rounded-lg border bg-white p-6">
        <h2 className="text-lg font-semibold text-gray-900 mb-4">Storage</h2>
        <h3 className="text-sm font-semibold text-gray-900 mb-4">
          {layout.central ? 'Response time and JVM gauge data' : 'Aggregate data (data.mv.db)'}
        </h3>
        {rollupDays.map((days, i) => (
          <GtFormGroup
            key={i}
            label={`${rollupLabels[i]} aggregates`}
            required
            pattern={PATTERNS.integer}
            value={days}
          >
            <div className="flex items-center gap-2">
              <Input
                type="number"
                value={days}
                onChange={(e) => updateRollupDay(i, parseNumInput(e.target.value))}
                className="w-24"
                disabled={!layout.adminEdit}
              />
              <span className="text-sm text-gray-500">days</span>
            </div>
          </GtFormGroup>
        ))}

        {layout.central && queryRollupDays.length > 0 && (
          <>
            <h3 className="text-sm font-semibold text-gray-900 mt-6 mb-4">
              Query and service call data
            </h3>
            {queryRollupDays.map((days, i) => (
              <GtFormGroup
                key={i}
                label={`${rollupLabels[i]} aggregates`}
                required
                pattern={PATTERNS.integer}
                value={days}
              >
                <div className="flex items-center gap-2">
                  <Input
                    type="number"
                    value={days}
                    onChange={(e) => updateQueryRollupDay(i, parseNumInput(e.target.value))}
                    className="w-24"
                    disabled={!layout.adminEdit}
                  />
                  <span className="text-sm text-gray-500">days</span>
                </div>
              </GtFormGroup>
            ))}
          </>
        )}

        {layout.central && profileRollupDays.length > 0 && (
          <>
            <h3 className="text-sm font-semibold text-gray-900 mt-6 mb-4">
              Profile data
            </h3>
            {profileRollupDays.map((days, i) => (
              <GtFormGroup
                key={i}
                label={`${rollupLabels[i]} aggregates`}
                required
                pattern={PATTERNS.integer}
                value={days}
              >
                <div className="flex items-center gap-2">
                  <Input
                    type="number"
                    value={days}
                    onChange={(e) => updateProfileRollupDay(i, parseNumInput(e.target.value))}
                    className="w-24"
                    disabled={!layout.adminEdit}
                  />
                  <span className="text-sm text-gray-500">days</span>
                </div>
              </GtFormGroup>
            ))}
          </>
        )}

        <GtFormGroup
          label="Trace data"
          required
          pattern={PATTERNS.integer}
          value={traceDays}
        >
          <div className="flex items-center gap-2">
            <Input
              type="number"
              value={traceDays}
              onChange={(e) => setTraceDays(parseNumInput(e.target.value))}
              className="w-24"
              disabled={!layout.adminEdit}
            />
            <span className="text-sm text-gray-500">days</span>
          </div>
        </GtFormGroup>

        {!layout.central && (
          <GtFormGroup
            label="Full query text"
            required
            pattern={PATTERNS.integer}
            value={fullQueryDays}
          >
            <div className="flex items-center gap-2">
              <Input
                type="number"
                value={fullQueryDays}
                onChange={(e) => setFullQueryDays(parseNumInput(e.target.value))}
                className="w-24"
                disabled={!layout.adminEdit}
              />
              <span className="text-sm text-gray-500">days</span>
            </div>
          </GtFormGroup>
        )}
      </div>

      {/* Capped database sizes (embedded only) */}
      {!layout.central && config.rollupCappedDatabaseSizesMb && (
        <div className="rounded-lg border bg-white p-6">
          <h3 className="text-sm font-semibold text-gray-900 mb-4">Capped database sizes (*.capped.db)</h3>
          {rollupCappedSizes.map((size, i) => (
            <GtFormGroup
              key={i}
              label={`${rollupLabels[i]} aggregates`}
              required
              pattern={PATTERNS.integer}
              value={size}
            >
              <div className="flex items-center gap-2">
                <Input
                  ref={i === 0 ? firstRollupCappedRef : undefined}
                  id={i === 0 ? 'rollup-capped-database-size' : undefined}
                  type="number"
                  value={size}
                  onChange={(e) => updateCappedSize(i, parseNumInput(e.target.value))}
                  className="w-24"
                  disabled={!layout.adminEdit}
                />
                <span className="text-sm text-gray-500">MB</span>
              </div>
            </GtFormGroup>
          ))}
          <GtFormGroup
            label="Trace detail"
            required
            pattern={PATTERNS.integer}
            value={traceCappedSize}
          >
            <div className="flex items-center gap-2">
              <Input
                ref={traceCappedRef}
                id="trace-capped-database-size"
                type="number"
                value={traceCappedSize}
                onChange={(e) => setTraceCappedSize(parseNumInput(e.target.value))}
                className="w-24"
                disabled={!layout.adminEdit}
              />
              <span className="text-sm text-gray-500">MB</span>
            </div>
          </GtFormGroup>
        </div>
      )}

      {/* H2 cache (embedded only) */}
      {!layout.central && (
        <div className="rounded-lg border bg-white p-6">
          <h3 className="text-sm font-semibold text-gray-900 mb-2">H2 cache</h3>
          <p className="text-xs text-gray-500 mb-4">
            In-memory H2 page cache (same JVM heap as the app), not disk.
          </p>
          <GtFormGroup
            label="Cache size"
            htmlFor="h2CacheMode"
            required={config.h2CacheMode !== 'auto'}
            pattern={config.h2CacheMode !== 'auto' ? PATTERNS.positiveInteger : undefined}
            value={config.h2CacheMode !== 'auto' ? h2CacheValue : undefined}
          >
            <div className="flex flex-wrap items-center gap-2">
              <select
                id="h2CacheMode"
                value={config.h2CacheMode ?? 'fixed'}
                onChange={(e) =>
                  setConfig({
                    ...config,
                    h2CacheMode: e.target.value as 'auto' | 'fixed' | 'percent',
                  })
                }
                disabled={!layout.adminEdit || !!config.h2CacheSystemPropertyOverride}
                className="flex h-9 rounded-md border border-gray-300 bg-white px-3 py-1 text-sm"
              >
                <option value="auto">Auto (128 MB target)</option>
                <option value="fixed">Fixed MB</option>
                <option value="percent">% of -Xmx</option>
              </select>
              <Input
                type="number"
                min={1}
                value={h2CacheValue}
                onChange={(e) => setH2CacheValue(parseNumInput(e.target.value))}
                disabled={
                  !layout.adminEdit ||
                  config.h2CacheMode === 'auto' ||
                  !!config.h2CacheSystemPropertyOverride
                }
                className="w-24"
              />
              {config.h2CacheMode === 'fixed' && (
                <span className="text-sm text-gray-500">MB</span>
              )}
              {config.h2CacheMode === 'percent' && (
                <span className="text-sm text-gray-500">% of -Xmx</span>
              )}
            </div>
            <p className="mt-2 text-xs text-gray-500">
              Effective after save: <strong>{config.effectiveH2CacheMb ?? 0}</strong> MB (heap {config.maxHeapMb ?? 0} MB).
            </p>
            {h2CacheWarning && (
              <p className="mt-1 text-xs text-amber-600">{h2CacheWarning}</p>
            )}
            {config.h2CacheSystemPropertyOverride && (
              <p className="mt-1 text-xs text-amber-600">
                Overridden by JVM property <code>-Dglowroot.internal.h2.cacheSize</code> (KB). Clear the property to use UI settings.
              </p>
            )}
          </GtFormGroup>
        </div>
      )}

      {layout.adminEdit && (
        <div className="rounded-lg border bg-white px-6 pb-6 pt-2">
          <GtButtonGroup onSave={handleSave} hasChanges={hasChanges}>
            {layout.central && (
              <Button
                variant="secondary"
                onClick={() =>
                  runAction(
                    '/backend/admin/update-cassandra-twcs-window-sizes',
                    'This could trigger a lot of window major compactions. Are you sure?',
                  )
                }
                loading={actionLoading === '/backend/admin/update-cassandra-twcs-window-sizes'}
              >
                Update TWCS window sizes
              </Button>
            )}
          </GtButtonGroup>
          {layout.central && (
            <p className="mt-2 text-xs text-gray-500">
              Note: Changing TTL only affects data written going forward.
            </p>
          )}
        </div>
      )}

      {/* Actions (embedded H2 only) */}
      {!layout.central && (layout.adminEdit || layout.offlineViewer) && (
        <div className="rounded-lg border bg-white p-6">
          <h3 className="text-sm font-semibold text-gray-900 mb-4">Actions</h3>
          <div className="flex flex-wrap gap-3">
            {!layout.central && (
              <>
                {(layout.adminEdit || layout.offlineViewer) && (
                  <>
                    <Button
                      variant="secondary"
                      onClick={() => runAction('/backend/admin/defrag-h2-data',
                        'This could take several minutes during which time Glowroot will not capture any new data. Continue?')}
                      loading={actionLoading === '/backend/admin/defrag-h2-data'}
                    >
                      Defrag H2 data
                    </Button>
                    <Button
                      variant="secondary"
                      onClick={() => runAction('/backend/admin/compact-h2-data',
                        'This could take several minutes during which time Glowroot will not capture any new data. Continue?')}
                      loading={actionLoading === '/backend/admin/compact-h2-data'}
                    >
                      Compact H2 data
                    </Button>
                    <Button
                      variant="secondary"
                      onClick={() => runAction('/backend/admin/analyze-h2-disk-space',
                        'This could take several minutes during which time Glowroot data capture will be impacted. Continue?')}
                      loading={actionLoading === '/backend/admin/analyze-h2-disk-space'}
                    >
                      Analyze H2 disk space
                    </Button>
                    <Button
                      variant="secondary"
                      onClick={() => runAction('/backend/admin/analyze-trace-counts',
                        'This could take several minutes during which time Glowroot data capture will be impacted. Continue?')}
                      loading={actionLoading === '/backend/admin/analyze-trace-counts'}
                    >
                      Analyze trace counts
                    </Button>
                  </>
                )}
                {layout.adminEdit && (
                  <Button
                    variant="destructive"
                    onClick={() => runAction('/backend/admin/delete-all-stored-data',
                      'This will delete all captured data, including aggregated transaction data, traces and gauge data. Are you sure?')}
                    loading={actionLoading === '/backend/admin/delete-all-stored-data'}
                  >
                    Delete all data
                  </Button>
                )}
              </>
            )}
          </div>
          {actionMessage && (
            <p className={`mt-3 text-sm font-medium ${actionMessage.type === 'success' ? 'text-green-600' : 'text-red-600'}`}>
              {actionMessage.text}
            </p>
          )}
          {h2Analysis && (() => {
            const fileSize = h2Analysis.h2DataFileSize ?? 0
            const liveBytes = h2Analysis.liveBytes ?? 0
            const reclaimableBytes = h2Analysis.reclaimableBytes ?? 0
            const livePct = fileSize > 0 ? Math.min(100, (100 * liveBytes) / fileSize) : 0
            const reclaimablePct = fileSize > 0 ? Math.min(100 - livePct, (100 * reclaimableBytes) / fileSize) : 0
            const showCompactCta =
              reclaimableBytes >= 64 * 1024 * 1024 || (fileSize > 0 && reclaimableBytes / fileSize >= 0.1)
            return (
              <div className="mt-6 space-y-4">
                <div className="text-sm">
                  <span className="font-semibold">H2 data file size:</span> {formatBytes(fileSize)}
                </div>
                {fileSize > 0 && (
                  <div className="space-y-2">
                    <div className="flex h-5 max-w-xl overflow-hidden border border-gray-400">
                      <div style={{ width: `${livePct}%`, backgroundColor: '#6c757d' }} title="Live table data" />
                      <div style={{ width: `${reclaimablePct}%`, backgroundColor: '#ffc107' }} title="Reclaimable with Compact" />
                    </div>
                    <p className="text-xs text-gray-600">
                      Live (referenced): {formatBytes(liveBytes)} &middot; Reclaimable with Compact: {formatBytes(reclaimableBytes)}
                    </p>
                    {showCompactCta && (
                      <div className="space-y-2 text-xs text-amber-700">
                        <p>
                          Compact recommended (~{formatBytes(reclaimableBytes)}). Lowering retention does not shrink the file until Compact runs.
                        </p>
                        {(layout.adminEdit || layout.offlineViewer) && (
                          <Button
                            size="sm"
                            variant="secondary"
                            onClick={() => runAction('/backend/admin/compact-h2-data',
                              'This could take several minutes during which time Glowroot will not capture any new data. Continue?')}
                            loading={actionLoading === '/backend/admin/compact-h2-data'}
                          >
                            Compact H2 data
                          </Button>
                        )}
                      </div>
                    )}
                  </div>
                )}
                {h2Analysis.tables && h2Analysis.tables.length > 0 && (
                  <table className="w-full text-sm border-collapse">
                    <thead>
                      <tr className="border-b text-left text-gray-600">
                        <th className="py-2 w-1/2">Table name</th>
                        <th className="py-2 w-1/4">Total bytes</th>
                        <th className="py-2 w-1/4">Row count</th>
                      </tr>
                    </thead>
                    <tbody>
                      {h2Analysis.tables.map((t) => (
                        <tr key={t.name} className="border-b last:border-b-0">
                          <td className="py-1.5 font-mono text-xs">{t.name}</td>
                          <td className="py-1.5 tabular-nums">{t.bytes.toLocaleString()}</td>
                          <td className="py-1.5 tabular-nums">{t.rows.toLocaleString()}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </div>
            )
          })()}
          {traceCountAnalysis && (
            <div className="mt-6 space-y-6">
              {traceCountAnalysis.overallCounts && traceCountAnalysis.overallCounts.length > 1 && (
                <table className="w-full text-sm border-collapse">
                  <thead>
                    <tr className="border-b">
                      <th colSpan={3} className="py-2 text-center font-semibold">By transaction type</th>
                    </tr>
                    <tr className="border-b text-left text-gray-600">
                      <th className="py-2">Transaction type</th>
                      <th className="py-2">Traces captured</th>
                      <th className="py-2">Traces captured because they were flagged as errors</th>
                    </tr>
                  </thead>
                  <tbody>
                    {traceCountAnalysis.overallCounts.map((row) => (
                      <tr key={row.transactionType} className="border-b last:border-b-0">
                        <td className="py-1.5">{row.transactionType}</td>
                        <td className="py-1.5 tabular-nums">{row.count.toLocaleString()}</td>
                        <td className="py-1.5 tabular-nums">{row.errorCount.toLocaleString()}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
              {traceCountAnalysis.counts && (
                <table className="w-full text-sm border-collapse">
                  <thead>
                    {traceCountAnalysis.overallCounts && traceCountAnalysis.overallCounts.length > 1 && (
                      <tr className="border-b">
                        <th colSpan={4} className="py-2 text-center font-semibold">By transaction name</th>
                      </tr>
                    )}
                    <tr className="border-b text-left text-gray-600">
                      <th className="py-2 w-1/5">Transaction type</th>
                      <th className="py-2 w-2/5">Transaction name</th>
                      <th className="py-2 w-1/5">Traces captured</th>
                      <th className="py-2 w-1/5">Traces captured because they were flagged as errors</th>
                    </tr>
                  </thead>
                  <tbody>
                    {traceCountAnalysis.counts.map((row, idx) => (
                      <tr key={`${row.transactionType}:${row.transactionName}:${idx}`} className="border-b last:border-b-0">
                        <td className="py-1.5">{row.transactionType}</td>
                        <td className="py-1.5 break-all">{row.transactionName}</td>
                        <td className="py-1.5 tabular-nums">{row.count.toLocaleString()}</td>
                        <td className="py-1.5 tabular-nums">{row.errorCount.toLocaleString()}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          )}
        </div>
      )}
      <div className="mt-4 text-xs text-gray-500">
        For more information and configuration details, see the{' '}
        <a
          href="https://github.com/glowroot/glowroot/wiki/Administration-Storage"
          target="_blank"
          rel="noopener noreferrer"
          className="text-blue-600 hover:underline"
        >
          Glowroot wiki
        </a>
        .
      </div>
    </div>
  )
}
