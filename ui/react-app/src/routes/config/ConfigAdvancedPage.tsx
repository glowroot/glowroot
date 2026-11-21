import { useCallback, useEffect, useState } from 'react'
import { useAgent } from '../../contexts/AgentContext'
import { apiGet, apiPost } from '../../lib/api'
import { GtFormGroup } from '../../components/shared/GtFormGroup'
import { GtButtonGroup } from '../../components/shared/GtButtonGroup'
import { Input } from '../../components/ui/input'
import { Checkbox } from '../../components/ui/checkbox'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'
import { SettingsPage } from '../../components/shared/SettingsPage'
import { PATTERNS } from '../../lib/validation'

interface AdvancedConfig {
  immediatePartialStoreThresholdSeconds?: number
  maxTransactionAggregates?: number
  maxQueryAggregates?: number
  maxServiceCallAggregates?: number
  maxTraceEntriesPerTransaction?: number
  maxProfileSamplesPerTransaction?: number
  mbeanGaugeNotFoundDelaySeconds?: number
  weavingTimer?: boolean
  version: string
}

const WIKI_ADVANCED =
  'https://github.com/glowroot/glowroot/wiki/Advanced-and-config.json'

export function ConfigAdvancedPage() {
  const { agentRollupId, agentRollup, isRollup } = useAgent()
  const [config, setConfig] = useState<AdvancedConfig | null>(null)
  const [originalConfig, setOriginalConfig] = useState<AdvancedConfig | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)

  const canEdit = agentRollup?.permissions?.config?.edit?.advanced ?? false

  const load = useCallback(async () => {
    if (agentRollupId === null) return
    try {
      const resp = await apiGet<AdvancedConfig>('/backend/config/advanced', {
        'agent-rollup-id': agentRollupId,
      })
      setConfig({ ...resp })
      setOriginalConfig({ ...resp })
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
    }
  }, [agentRollupId])

  useEffect(() => { load() }, [load])

  const hasChanges = config && originalConfig
    ? JSON.stringify(config) !== JSON.stringify(originalConfig)
    : false

  async function handleSave() {
    if (agentRollupId === null || !config) return
    const requiredFields: Array<[keyof AdvancedConfig, string]> = !isRollup
      ? [
          ['immediatePartialStoreThresholdSeconds', 'Immediate partial trace store threshold'],
          ['maxTransactionAggregates', 'Max transaction aggregates'],
          ['maxQueryAggregates', 'Max query aggregates'],
          ['maxServiceCallAggregates', 'Max service call aggregates'],
          ['maxTraceEntriesPerTransaction', 'Max trace entries per transaction'],
          ['maxProfileSamplesPerTransaction', 'Max profile samples per transaction'],
          ['mbeanGaugeNotFoundDelaySeconds', 'MBean gauge not found delay'],
        ]
      : [
          ['maxQueryAggregates', 'Max query aggregates'],
          ['maxServiceCallAggregates', 'Max service call aggregates'],
        ]
    for (const [key, label] of requiredFields) {
      const val = config[key]
      if (typeof val !== 'number' || Number.isNaN(val)) {
        throw new Error(`${label} is required`)
      }
    }
    const resp = await apiPost<AdvancedConfig>(
      '/backend/config/advanced?agent-rollup-id=' + encodeURIComponent(agentRollupId),
      config
    )
    setConfig({ ...resp })
    setOriginalConfig({ ...resp })
  }

  if (loading) return <PageSpinner />
  if (error) return <HttpError error={error} />
  if (!config) return null

  function setField<K extends keyof AdvancedConfig>(key: K, value: AdvancedConfig[K]) {
    setConfig({ ...config!, [key]: value })
  }

  return (
    <SettingsPage
      title="Advanced"
      description="Capture limits and low-level agent behavior. Prefer small changes — these affect memory, trace size, and storage."
    >
      {!isRollup && (
        <GtFormGroup
          label="Immediate partial trace store threshold"
          required
          pattern={PATTERNS.integer}
          value={config.immediatePartialStoreThresholdSeconds ?? ''}
          helpText="Store a partial trace once a transaction runs this long (guards hung/never-finishing work). If that transaction has a longer slow threshold, this waits until the slow threshold is also exceeded."
        >
          <div className="flex items-center gap-2">
            <Input
              type="number"
              value={config.immediatePartialStoreThresholdSeconds ?? ''}
              onChange={(e) => setField('immediatePartialStoreThresholdSeconds', e.target.value === '' ? undefined : parseInt(e.target.value, 10))}
              className="w-24"
              disabled={!canEdit}
            />
            <span className="text-sm text-gray-500">seconds</span>
          </div>
        </GtFormGroup>
      )}
      {!isRollup && (
        <GtFormGroup
          label="Max transaction aggregates per type"
          required
          pattern={PATTERNS.integer}
          value={config.maxTransactionAggregates ?? ''}
          helpText='Cap distinct transaction names stored per type per minute. Names that miss the cut still roll into the "Overall" aggregate.'
        >
          <Input
            type="number"
            value={config.maxTransactionAggregates ?? ''}
            onChange={(e) => setField('maxTransactionAggregates', e.target.value === '' ? undefined : parseInt(e.target.value, 10))}
            className="w-24"
            disabled={!canEdit}
          />
        </GtFormGroup>
      )}
      <GtFormGroup
        label="Max query aggregates per transaction aggregate"
        required
        pattern={PATTERNS.integer}
        value={config.maxQueryAggregates ?? ''}
        helpText='Cap distinct queries kept per transaction aggregate per minute. Overflow lands in "LIMIT EXCEEDED BUCKET"; the same cap applies on rollups and in the UI.'
      >
        <Input
          type="number"
          value={config.maxQueryAggregates ?? ''}
          onChange={(e) => setField('maxQueryAggregates', e.target.value === '' ? undefined : parseInt(e.target.value, 10))}
          className="w-24"
          disabled={!canEdit}
        />
      </GtFormGroup>
      <GtFormGroup
        label="Max service call aggregates per transaction aggregate"
        required
        pattern={PATTERNS.integer}
        value={config.maxServiceCallAggregates ?? ''}
        helpText='Cap distinct service calls kept per transaction aggregate per minute. Overflow lands in "LIMIT EXCEEDED BUCKET"; the same cap applies on rollups and in the UI.'
      >
        <Input
          type="number"
          value={config.maxServiceCallAggregates ?? ''}
          onChange={(e) => setField('maxServiceCallAggregates', e.target.value === '' ? undefined : parseInt(e.target.value, 10))}
          className="w-24"
          disabled={!canEdit}
        />
      </GtFormGroup>
      {!isRollup && (
        <GtFormGroup
          label="Max trace entries per transaction"
          required
          pattern={PATTERNS.integer}
          value={config.maxTraceEntriesPerTransaction ?? ''}
          helpText="Cap entries on one transaction to bound memory for huge batch or background work."
        >
          <Input
            type="number"
            value={config.maxTraceEntriesPerTransaction ?? ''}
            onChange={(e) => setField('maxTraceEntriesPerTransaction', e.target.value === '' ? undefined : parseInt(e.target.value, 10))}
            className="w-24"
            disabled={!canEdit}
          />
        </GtFormGroup>
      )}
      {!isRollup && (
        <GtFormGroup
          label="Max profile samples per transaction"
          required
          pattern={PATTERNS.integer}
          value={config.maxProfileSamplesPerTransaction ?? ''}
          helpText="Cap profile samples per transaction. Samples merge when possible, so this can stay fairly high."
        >
          <Input
            type="number"
            value={config.maxProfileSamplesPerTransaction ?? ''}
            onChange={(e) => setField('maxProfileSamplesPerTransaction', e.target.value === '' ? undefined : parseInt(e.target.value, 10))}
            className="w-24"
            disabled={!canEdit}
          />
        </GtFormGroup>
      )}
      {!isRollup && config.mbeanGaugeNotFoundDelaySeconds !== undefined && (
        <GtFormGroup
          label="MBean gauge not found delay"
          pattern={PATTERNS.integer}
          value={config.mbeanGaugeNotFoundDelaySeconds ?? ''}
          helpText="Wait this long before retrying a configured gauge whose MBean is missing."
        >
          <div className="flex items-center gap-2">
            <Input
              type="number"
              value={config.mbeanGaugeNotFoundDelaySeconds ?? ''}
              onChange={(e) => setField('mbeanGaugeNotFoundDelaySeconds', e.target.value === '' ? undefined : parseInt(e.target.value, 10))}
              className="w-24"
              disabled={!canEdit}
            />
            <span className="text-sm text-gray-500">seconds</span>
          </div>
        </GtFormGroup>
      )}
      {!isRollup && config.weavingTimer !== undefined && (
        <GtFormGroup
          label="Weaving timer"
          helpText="Record time spent weaving classes (diagnostic)."
        >
          <label className="flex items-center gap-2 text-sm">
            <Checkbox
              checked={config.weavingTimer}
              onCheckedChange={(c) => setField('weavingTimer', !!c)}
              disabled={!canEdit}
            />
            Capture weaving timing
          </label>
        </GtFormGroup>
      )}
      {canEdit && (
        <GtButtonGroup onSave={handleSave} hasChanges={hasChanges} />
      )}
      <div className="mt-6 text-xs text-gray-500">
        For more information and configuration details, see the{' '}
        <a
          href={WIKI_ADVANCED}
          target="_blank"
          rel="noopener noreferrer"
          className="text-blue-600 hover:underline"
        >
          Glowroot wiki
        </a>
        .
      </div>
    </SettingsPage>
  )
}
