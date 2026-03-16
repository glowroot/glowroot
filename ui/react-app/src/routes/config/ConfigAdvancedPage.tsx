import { useCallback, useEffect, useState } from 'react'
import { useAgent } from '../../contexts/AgentContext'
import { apiGet, apiPost } from '../../lib/api'
import { GtFormGroup } from '../../components/shared/GtFormGroup'
import { GtButtonGroup } from '../../components/shared/GtButtonGroup'
import { Input } from '../../components/ui/input'
import { Checkbox } from '../../components/ui/checkbox'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'
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
    <div className="rounded-lg border bg-white p-6">
      <h2 className="text-lg font-semibold text-gray-900 mb-4">Advanced</h2>
      {!isRollup && (
        <GtFormGroup
          label="Immediate partial trace store threshold"
          required
          pattern={PATTERNS.integer}
          value={config.immediatePartialStoreThresholdSeconds ?? ''}
          helpText="Any transaction that exceeds this amount of time will have its partial trace stored to disk immediately (prior to its completion). This is to guard against transactions which never complete (or at least run long enough and do enough harm to hang the jvm). In the case where some transactions have been configured with a very long slow threshold, the immediate partial trace store threshold does not take effect on those transactions until they have exceeded their slow threshold."
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
          helpText={'The maximum number of transaction aggregates (aggregated by transaction name) that are stored per transaction type for any given minute. This is used to limit memory and storage when there are lots of distinct transaction names. Any transactions that do not make the cut are still aggregated and stored in the "Overall" transaction aggregate. This limit is applied per transaction type.'}
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
        helpText={'The maximum number of query aggregates (aggregated by query text) that are stored per transaction aggregate for any given minute. This is used to limit memory and storage when there are lots of distinct queries. During any given minute, up to ten times this number of query aggregates are stored in memory for the overall transaction aggregate and up to two times this number are stored in memory for named transaction aggregates. At the end of each minute, up to this number of query aggregates are stored to disk per transaction aggregate. Any queries / query aggregates that do not make the cut are aggregated and stored under the query text "LIMIT EXCEEDED BUCKET" (for their respective query type). In addition to this limit being applied during initial collection, it is also applied during rollups and when displaying query aggregates in the UI (over still larger time spans).'}
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
        helpText={'The maximum number of service call aggregates (aggregated by service call text) that are stored per transaction aggregate for any given minute. This is used to limit memory and storage when there are lots of distinct service calls. During any given minute, up to ten times this number of service call aggregates are stored in memory for the overall transaction aggregate and up to two times this number are stored in memory for named transaction aggregates. At the end of each minute, up to this number of service call aggregates are stored to disk per transaction aggregate. Any service calls / service call aggregates that do not make the cut are aggregated and stored under the service call text "LIMIT EXCEEDED BUCKET" (for their respective service call type). In addition to this limit being applied during initial collection, it is also applied during rollups and when displaying service call aggregates in the UI (over still larger time spans).'}
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
          helpText="Maximum number of trace entries collected for any given transaction. This is used to limit the memory of very long transactions that would capture potentially hundreds of thousands of trace entries (e.g. large batch or background operations). Also, the UI is not really optimized for viewing super large numbers of trace entries."
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
          helpText="Maximum number of profile samples collected for any given transaction. This is used to limit the memory of very long transactions that would capture potentially hundreds of thousands of profile samples (e.g. large batch or background operations). Profile samples are merged where possible so this can generally be quite large."
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
        <GtFormGroup label="Weaving timer">
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
    </div>
  )
}
