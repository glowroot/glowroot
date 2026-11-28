import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Plus, Trash2 } from 'lucide-react'
import { useAgent } from '../../contexts/AgentContext'
import { apiGet, apiPost } from '../../lib/api'
import { PATTERNS } from '../../lib/validation'
import { GtFormGroup } from '../../components/shared/GtFormGroup'
import { GtButtonGroup } from '../../components/shared/GtButtonGroup'
import { Input } from '../../components/ui/input'
import { Checkbox } from '../../components/ui/checkbox'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'
import { SettingsPage } from '../../components/shared/SettingsPage'
import { SettingsSection } from '../../components/shared/SettingsSection'
import { Select } from '../../components/ui/select'

interface SlowThresholdOverride {
  transactionType: string
  transactionName: string
  user: string
  thresholdMillis: number | null
}

interface TransactionConfig {
  slowThresholdMillis: number
  profilingIntervalMillis: number
  captureThreadStats: boolean
  slowThresholdOverrides: SlowThresholdOverride[]
  version: string
}

interface TransactionData {
  config: TransactionConfig
  defaultTransactionType: string
  allTransactionTypes: string[]
}

export function ConfigTransactionPage() {
  const { agentId, agentRollup, agentQueryString } = useAgent()
  const [data, setData] = useState<TransactionData | null>(null)
  const [config, setConfig] = useState<TransactionConfig | null>(null)
  const [originalConfig, setOriginalConfig] = useState<TransactionConfig | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)

  const canEdit = agentRollup?.permissions?.config?.edit?.transaction ?? false

  function onNewData(resp: TransactionData) {
    setData(resp)
    setConfig({
      ...resp.config,
      slowThresholdOverrides: resp.config.slowThresholdOverrides.map((o) => ({ ...o })),
    })
    setOriginalConfig({
      ...resp.config,
      slowThresholdOverrides: resp.config.slowThresholdOverrides.map((o) => ({ ...o })),
    })
  }

  const load = useCallback(async () => {
    if (agentId === null) return
    try {
      const resp = await apiGet<TransactionData>('/backend/config/transaction', {
        'agent-id': agentId,
      })
      onNewData(resp)
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
    }
  }, [agentId])

  useEffect(() => { load() }, [load])

  const hasChanges = config && originalConfig
    ? JSON.stringify(config) !== JSON.stringify(originalConfig)
    : false

  async function handleSave() {
    if (agentId === null || !config) return
    if (
      config.slowThresholdMillis === undefined ||
      config.slowThresholdMillis === null ||
      (config.slowThresholdMillis as unknown) === '' ||
      Number.isNaN(config.slowThresholdMillis)
    ) {
      throw new Error('Slow threshold is required')
    }
    if (
      config.profilingIntervalMillis === undefined ||
      config.profilingIntervalMillis === null ||
      (config.profilingIntervalMillis as unknown) === '' ||
      Number.isNaN(config.profilingIntervalMillis)
    ) {
      throw new Error('Profiling interval is required')
    }
    const resp = await apiPost<TransactionData>(
      '/backend/config/transaction?agent-id=' + encodeURIComponent(agentId),
      config
    )
    onNewData(resp)
  }

  function addOverride() {
    if (!config || !data) return
    setConfig({
      ...config,
      slowThresholdOverrides: [
        ...config.slowThresholdOverrides,
        {
          transactionType: data.defaultTransactionType,
          transactionName: '',
          user: '',
          thresholdMillis: null,
        },
      ],
    })
  }

  function removeOverride(index: number) {
    if (!config) return
    const updated = [...config.slowThresholdOverrides]
    updated.splice(index, 1)
    setConfig({ ...config, slowThresholdOverrides: updated })
  }

  function updateOverride(index: number, field: keyof SlowThresholdOverride, value: unknown) {
    if (!config) return
    const updated = [...config.slowThresholdOverrides]
    updated[index] = { ...updated[index], [field]: value }
    setConfig({ ...config, slowThresholdOverrides: updated })
  }

  if (loading) return <PageSpinner />
  if (error) return <HttpError error={error} />
  if (!config || !data) return null

  return (
    <SettingsPage title="Transactions">
<GtFormGroup
        label="Slow threshold"
        required
        pattern={PATTERNS.integer}
        value={config.slowThresholdMillis ?? ''}
        helpText="Any transaction that exceeds this amount of time will be viewable immediately as an active trace and the trace will be stored to disk at its completion."
      >
        <div className="flex items-center gap-2">
          <Input
            type="number"
            value={config.slowThresholdMillis ?? ''}
            onChange={(e) => {
              const val = e.target.value
              setConfig({ ...config, slowThresholdMillis: (val === '' ? '' : parseInt(val, 10)) as number })
            }}
            className="w-24"
            disabled={!canEdit}
          />
          <span className="text-sm text-gray-500">milliseconds</span>
        </div>
      </GtFormGroup>
      <GtFormGroup
        label="Profiling interval"
        required
        pattern={PATTERNS.integer}
        value={config.profilingIntervalMillis ?? ''}
        helpText="The interval at which the profiler samples stack traces. Each sampling time is randomized within its given interval in order to avoid periodicity bias."
      >
        <div className="flex items-center gap-2">
          <Input
            type="number"
            value={config.profilingIntervalMillis ?? ''}
            onChange={(e) => {
              const val = e.target.value
              setConfig({ ...config, profilingIntervalMillis: (val === '' ? '' : parseInt(val, 10)) as number })
            }}
            className="w-24"
            disabled={!canEdit}
          />
          <span className="text-sm text-gray-500">milliseconds</span>
        </div>
      </GtFormGroup>
      <GtFormGroup label="Thread stats">
        <label className="flex items-center gap-2 text-sm">
          <Checkbox
            checked={config.captureThreadStats}
            onCheckedChange={(c) => setConfig({ ...config, captureThreadStats: !!c })}
            disabled={!canEdit}
          />
          Capture JVM thread stats
        </label>
        <p className="mt-1.5 text-xs text-gray-500">
          Capture CPU time, blocked time, waited time and allocated memory if available.{' '}
          {(agentRollup?.permissions as { jvm?: { capabilities?: boolean } } | undefined)?.jvm?.capabilities && (
            <>
              See{' '}
              <Link
                to={'/modern/jvm/capabilities' + agentQueryString()}
                className="text-blue-600 hover:underline"
              >
                JVM Capabilities
              </Link>
              .{' '}
            </>
          )}
          Capturing this data has some overhead (around 5 microseconds per transaction).
        </p>
      </GtFormGroup>

      <SettingsSection title="Slow threshold overrides">
        {config.slowThresholdOverrides.map((override, i) => (
          <div
            key={i}
            className={i === 0 ? 'pb-4' : 'border-t border-gray-100 py-4'}
          >
            <GtFormGroup
              label="Transaction type"
              required
              value={override.transactionType}
              helpText="The transaction type this override applies to."
            >
              <Select
                value={override.transactionType}
                onChange={(e) => updateOverride(i, 'transactionType', e.target.value)}
                disabled={!canEdit}
              >
                {override.transactionType && !data.allTransactionTypes.includes(override.transactionType) && (
                  <option value={override.transactionType} disabled>{override.transactionType}</option>
                )}
                {data.allTransactionTypes.map((tt) => (
                  <option key={tt} value={tt}>{tt}</option>
                ))}
              </Select>
            </GtFormGroup>
            <GtFormGroup
              label="Transaction name"
              helpText="The transaction name this override applies to. If empty, this override will apply to all transaction names."
            >
              <Input
                value={override.transactionName}
                onChange={(e) => updateOverride(i, 'transactionName', e.target.value)}
                disabled={!canEdit}
              />
            </GtFormGroup>
            <GtFormGroup
              label="User"
              helpText="The user this override applies to. If empty, this override will apply to all users. Case insensitive comparison is performed."
            >
              <Input
                value={override.user}
                onChange={(e) => updateOverride(i, 'user', e.target.value)}
                disabled={!canEdit}
              />
            </GtFormGroup>
            <GtFormGroup
              label="Slow threshold"
              required
              pattern={PATTERNS.integer}
              value={override.thresholdMillis ?? ''}
            >
              <div className="flex items-center gap-2">
                <Input
                  type="number"
                  value={override.thresholdMillis ?? ''}
                  onChange={(e) => {
                    const val = e.target.value
                    updateOverride(i, 'thresholdMillis', val === '' ? null : parseInt(val, 10))
                  }}
                  className="w-24"
                  disabled={!canEdit}
                />
                <span className="text-sm text-gray-500">milliseconds</span>
              </div>
            </GtFormGroup>
            {canEdit && (
              <div className="sm:ml-56 mt-1">
                <button
                  type="button"
                  onClick={() => removeOverride(i)}
                  className="inline-flex items-center gap-1 text-sm text-gray-500 hover:text-red-600"
                >
                  <Trash2 className="h-3.5 w-3.5" />
                  Remove
                </button>
              </div>
            )}
          </div>
        ))}
        {canEdit && (
          <button
            type="button"
            onClick={addOverride}
            className="mt-1 inline-flex items-center gap-1 text-sm text-[#417998] hover:text-[#2f586e]"
          >
            <Plus className="h-3.5 w-3.5" />
            Add override
          </button>
        )}
      </SettingsSection>

      {canEdit && (
        <GtButtonGroup onSave={handleSave} hasChanges={hasChanges} />
      )}
    </SettingsPage>
  )
}
