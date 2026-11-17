import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useAgent } from '../../contexts/AgentContext'
import { useLayout } from '../../contexts/LayoutContext'
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
import { cn } from '../../lib/utils'

const selectClass =
  'h-8 max-w-xs rounded-md border border-gray-300 bg-white px-2.5 text-sm focus-visible:outline-none focus-visible:border-[#417998] focus-visible:ring-1 focus-visible:ring-[#417998] disabled:cursor-not-allowed disabled:bg-gray-50 disabled:opacity-60'

interface MethodSignature {
  name: string
  parameterTypes: string[]
  returnType: string
  modifiers: string[]
  notAvailable?: boolean
}

interface InstrumentationConfig {
  className: string
  classAnnotation: string
  subTypeRestriction: string
  superTypeRestriction: string
  methodName: string
  methodAnnotation: string
  methodParameterTypes: string[]
  methodReturnType: string
  methodModifiers: string[]
  nestingGroup: string
  order: number
  captureKind: string
  alreadyInTransactionBehavior: string | null
  transactionType: string
  transactionNameTemplate: string
  transactionUserTemplate: string
  transactionOuter: boolean
  transactionSlowThresholdMillis?: number | null
  traceEntryMessageTemplate: string
  traceEntryCaptureSelfNested: boolean
  traceEntryStackThresholdMillis?: number | null
  timerName: string
  enabledProperty: string
  traceEntryEnabledProperty: string
  version?: string
}

interface InstrumentationData {
  config: InstrumentationConfig
  agentNotConnected?: boolean
  methodSignatures?: MethodSignature[]
}

const NEW_CONFIG: InstrumentationConfig = {
  className: '',
  classAnnotation: '',
  subTypeRestriction: '',
  superTypeRestriction: '',
  methodName: '',
  methodAnnotation: '',
  methodParameterTypes: ['..'],
  methodReturnType: '',
  methodModifiers: [],
  nestingGroup: '',
  order: 0,
  captureKind: 'transaction',
  alreadyInTransactionBehavior: 'capture-trace-entry',
  transactionType: '',
  transactionNameTemplate: '',
  transactionUserTemplate: '',
  transactionOuter: false,
  traceEntryMessageTemplate: '',
  traceEntryCaptureSelfNested: false,
  timerName: '',
  enabledProperty: '',
  traceEntryEnabledProperty: '',
}

function isSignatureAll(sig: MethodSignature): boolean {
  return sig.modifiers.length === 0 && sig.returnType === ''
    && sig.parameterTypes.length === 1 && sig.parameterTypes[0] === '..'
}

function signatureText(sig: MethodSignature | undefined): string {
  if (!sig || !sig.name || sig.name.includes('*') || sig.name.includes('|') || isSignatureAll(sig)) {
    return 'any signature'
  }
  let text = ''
  for (const mod of sig.modifiers || []) {
    text += mod + ' '
  }
  text += sig.returnType + ' ' + sig.name + '('
  text += (sig.parameterTypes || []).join(', ')
  text += ')'
  return text
}

export function ConfigInstrumentationPage() {
  const { agentId, agentRollup, agentQueryString } = useAgent()
  const { layout } = useLayout()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const version = searchParams.get('v')
  const isNew = !version
  const [lastSigClassName, setLastSigClassName] = useState('')
  const [lastSigMethodName, setLastSigMethodName] = useState('')
  const [classNameSuggestions, setClassNameSuggestions] = useState<string[]>([])
  const [methodNameSuggestions, setMethodNameSuggestions] = useState<string[]>([])
  const [showClassSuggestions, setShowClassSuggestions] = useState(false)
  const [showMethodSuggestions, setShowMethodSuggestions] = useState(false)
  const [showTransactionOverrides, setShowTransactionOverrides] = useState(false)
  const [showExportModal, setShowExportModal] = useState(false)

  const [config, setConfig] = useState<InstrumentationConfig>({ ...NEW_CONFIG })
  const [originalConfig, setOriginalConfig] = useState<InstrumentationConfig>({ ...NEW_CONFIG })
  const [agentNotConnected, setAgentNotConnected] = useState(false)
  const [methodSignatures, setMethodSignatures] = useState<MethodSignature[]>([])
  const [selectedSigIndex, setSelectedSigIndex] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)

  const canEdit = agentRollup?.permissions?.config?.edit?.instrumentation ?? false

  function initMethodSignatures(methodName: string) {
    const defaultSig: MethodSignature = {
      name: methodName,
      parameterTypes: ['..'],
      returnType: '',
      modifiers: [],
    }
    setMethodSignatures([defaultSig])
    setSelectedSigIndex(0)
  }

  const load = useCallback(async () => {
    if (agentId === null) return
    try {
      if (isNew) {
        if (layout.central) {
          const connected = await apiGet<boolean>('/backend/config/new-instrumentation-check-agent-connected', {
            'agent-id': agentId,
          })
          setAgentNotConnected(!connected)
        }
        setConfig({ ...NEW_CONFIG })
        setOriginalConfig({ ...NEW_CONFIG })
        initMethodSignatures('')
      } else if (version) {
        const resp = await apiGet<InstrumentationData>('/backend/config/instrumentation', {
          'agent-id': agentId,
          version,
        })
        setConfig({ ...resp.config })
        setOriginalConfig({ ...resp.config })
        setAgentNotConnected(!!resp.agentNotConnected)
        setLastSigClassName(resp.config.className || '')
        setLastSigMethodName(resp.config.methodName || '')
        if (
          resp.config.captureKind !== 'transaction' &&
          (resp.config.transactionType ||
            resp.config.transactionNameTemplate ||
            resp.config.transactionSlowThresholdMillis != null)
        ) {
          setShowTransactionOverrides(true)
        }
        // Build method signatures
        const sigs: MethodSignature[] = [
          { name: resp.config.methodName, parameterTypes: ['..'], returnType: '', modifiers: [] },
          ...(resp.methodSignatures || []),
        ]
        // Find matching signature
        let matchIdx = sigs.findIndex(
          (s) =>
            JSON.stringify(s.parameterTypes) === JSON.stringify(resp.config.methodParameterTypes) &&
            s.returnType === resp.config.methodReturnType &&
            JSON.stringify(s.modifiers) === JSON.stringify(resp.config.methodModifiers)
        )
        if (matchIdx < 0) {
          matchIdx = sigs.findIndex(
            (s) =>
              JSON.stringify(s.parameterTypes) === JSON.stringify(resp.config.methodParameterTypes)
          )
        }
        if (matchIdx < 0 && resp.config.methodName) {
          sigs.push({
            name: resp.config.methodName,
            parameterTypes: resp.config.methodParameterTypes || [],
            returnType: resp.config.methodReturnType || '',
            modifiers: resp.config.methodModifiers || [],
            notAvailable: true,
          })
          matchIdx = sigs.length - 1
        }
        setMethodSignatures(sigs)
        setSelectedSigIndex(matchIdx >= 0 ? matchIdx : 0)
      }
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
    }
  }, [agentId, isNew, version, layout.central])

  useEffect(() => { load() }, [load])

  async function fetchMethodSignatures(className: string, methodName: string) {
    if (agentNotConnected || agentId === null || !className || !methodName) {
      initMethodSignatures(methodName)
      return
    }
    if (methodName.includes('*') || methodName.includes('|')) {
      const sig: MethodSignature = { name: methodName, parameterTypes: ['..'], returnType: '', modifiers: [] }
      setMethodSignatures([sig])
      setSelectedSigIndex(0)
      setConfig((c) => ({ ...c, methodParameterTypes: ['..'], methodReturnType: '', methodModifiers: [] }))
      return
    }
    setLastSigClassName(className)
    setLastSigMethodName(methodName)
    if (!className || !methodName) {
      initMethodSignatures(methodName)
      return
    }
    try {
      const resp = await apiGet<MethodSignature[]>('/backend/config/method-signatures', {
        'agent-id': agentId,
        'class-name': className,
        'method-name': methodName,
      })
      const sigs: MethodSignature[] = [
        { name: methodName, parameterTypes: ['..'], returnType: '', modifiers: [] },
        ...resp,
      ]
      setMethodSignatures(sigs)
      setSelectedSigIndex(0)
      setConfig((c) => ({ ...c, methodParameterTypes: ['..'], methodReturnType: '', methodModifiers: [] }))
    } catch {
      initMethodSignatures(methodName)
    }
  }

  function handleSignatureChange(index: number) {
    setSelectedSigIndex(index)
    const sig = methodSignatures[index]
    if (sig) {
      setConfig((c) => ({
        ...c,
        methodParameterTypes: sig.parameterTypes,
        methodReturnType: sig.returnType || '',
        methodModifiers: [...(sig.modifiers || [])],
      }))
    }
  }

  const captureKind = config.captureKind
  const showTimer = captureKind === 'timer' || captureKind === 'trace-entry' || captureKind === 'transaction'
  const showTraceEntry = captureKind === 'trace-entry' || captureKind === 'transaction'
  const showTransaction = captureKind === 'transaction'
  const showTraceEntryStackThreshold = captureKind === 'trace-entry'
    || (captureKind === 'transaction' && config.alreadyInTransactionBehavior !== 'do-nothing')
  const showTraceEntryCaptureSelfNested = captureKind === 'trace-entry'
    || (captureKind === 'transaction' && config.alreadyInTransactionBehavior === 'capture-trace-entry')

  function handleCaptureKindChange(newKind: string) {
    const updated = { ...config, captureKind: newKind }
    if (newKind !== 'transaction') {
      updated.alreadyInTransactionBehavior = null
    } else if (!updated.alreadyInTransactionBehavior) {
      updated.alreadyInTransactionBehavior = 'capture-trace-entry'
    }
    if (newKind !== 'timer' && newKind !== 'trace-entry' && newKind !== 'transaction') {
      updated.timerName = ''
    }
    if (newKind !== 'trace-entry' && newKind !== 'transaction') {
      updated.traceEntryMessageTemplate = ''
      updated.traceEntryCaptureSelfNested = false
      updated.traceEntryStackThresholdMillis = null
    }
    setConfig(updated)
  }

  const hasChanges = (config.className || originalConfig.className)
    && JSON.stringify(config) !== JSON.stringify(originalConfig)

  async function handleSave() {
    if (agentId === null) return
    const endpoint = isNew
      ? '/backend/config/instrumentation/add'
      : '/backend/config/instrumentation/update'
    const resp = await apiPost<InstrumentationData>(
      endpoint + '?agent-id=' + encodeURIComponent(agentId),
      config
    )
    setConfig({ ...resp.config })
    setOriginalConfig({ ...resp.config })
    if (isNew) {
      navigate(
        `/modern/config/instrumentation?agent-id=${encodeURIComponent(agentId)}&v=${encodeURIComponent(resp.config.version!)}`,
        { replace: true }
      )
    }
  }

  async function handleDelete() {
    if (agentId === null) return
    await apiPost(
      '/backend/config/instrumentation/remove?agent-id=' + encodeURIComponent(agentId),
      { versions: [config.version] }
    )
    navigate(`/modern/config/instrumentation-list?agent-id=${encodeURIComponent(agentId)}`, { replace: true })
  }

  if (loading) return <PageSpinner />
  if (error) return <HttpError error={error} />

  return (
    <SettingsPage
      title={isNew ? 'New' : config.className + '::' + config.methodName}
      description="Custom pointcut: which methods to weave and what to capture (transaction, entry, or timer)."
      back={{ to: '/modern/config/instrumentation-list' + agentQueryString(), label: 'Instrumentation' }}
      wide
    >
        {agentNotConnected && (
          <p className="mb-3 text-sm text-amber-600">
            Agent is not connected. Auto-completion is not available.
          </p>
        )}

      <SettingsSection first title="Target" className="mt-0 border-0 pt-0">
        <div className="grid gap-x-6 gap-y-1 md:grid-cols-2">
          <GtFormGroup
            label="Class name"
            required={!config.classAnnotation}
            value={config.className}
            layout="stack"
            compact
            controlClassName="max-w-none"
            helpText="Class or interface that owns the method(s)."
          >
        <div className="relative">
          <Input
            value={config.className}
            onChange={(e) => {
              const val = e.target.value
              setConfig({ ...config, className: val })
              if (agentId && canEdit && val.trim().length > 0) {
                setShowClassSuggestions(true)
                apiGet<string[]>('/backend/config/matching-class-names', {
                  'agent-id': agentId,
                  'partial-class-name': val,
                  limit: 10,
                })
                  .then((items) => setClassNameSuggestions(items))
                  .catch(() => setClassNameSuggestions([]))
              } else {
                setClassNameSuggestions([])
                setShowClassSuggestions(false)
              }
            }}
            onBlur={() => {
              setTimeout(() => setShowClassSuggestions(false), 150)
              if (config.className !== lastSigClassName) {
                if (config.methodName) {
                  fetchMethodSignatures(config.className, config.methodName)
                } else {
                  setLastSigClassName(config.className)
                }
              }
            }}
            disabled={!canEdit}
            placeholder="e.g. com.example.MyClass"
          />
          {showClassSuggestions && classNameSuggestions.length > 0 && (
            <ul className="absolute z-10 mt-1 max-h-48 w-full overflow-auto rounded-md border border-gray-300 bg-white py-1 text-sm shadow-lg">
              {classNameSuggestions.map((cls) => (
                <li
                  key={cls}
                  className="cursor-pointer px-3 py-1.5 hover:bg-blue-50"
                  onMouseDown={(e) => {
                    e.preventDefault()
                    setConfig({ ...config, className: cls })
                    setShowClassSuggestions(false)
                    if (cls !== lastSigClassName && config.methodName) {
                      fetchMethodSignatures(cls, config.methodName)
                    } else {
                      setLastSigClassName(cls)
                    }
                  }}
                >
                  {cls}
                </li>
              ))}
            </ul>
          )}
        </div>
      </GtFormGroup>

      <GtFormGroup
        label="Method name"
        required={!config.methodAnnotation}
        value={config.methodName}
        layout="stack"
        compact
        controlClassName="max-w-none"
        helpText="Also weaves overrides / interface implementations. Use * wildcards; separate several names with |."
      >
        <div className="relative">
          <Input
            value={config.methodName}
            onChange={(e) => {
              const val = e.target.value
              setConfig({ ...config, methodName: val })
              if (agentId && canEdit && config.className && val.trim().length > 0) {
                setShowMethodSuggestions(true)
                apiGet<string[]>('/backend/config/matching-method-names', {
                  'agent-id': agentId,
                  'class-name': config.className,
                  'partial-method-name': val,
                  limit: 10,
                })
                  .then((items) => setMethodNameSuggestions(items))
                  .catch(() => setMethodNameSuggestions([]))
              } else {
                setMethodNameSuggestions([])
                setShowMethodSuggestions(false)
              }
            }}
            onBlur={() => {
              setTimeout(() => setShowMethodSuggestions(false), 150)
              if (config.methodName !== lastSigMethodName) {
                if (config.className && config.methodName) {
                  fetchMethodSignatures(config.className, config.methodName)
                } else {
                  initMethodSignatures(config.methodName)
                  setLastSigMethodName(config.methodName)
                }
              }
            }}
            disabled={!canEdit}
            placeholder="e.g. execute"
          />
          {showMethodSuggestions && methodNameSuggestions.length > 0 && (
            <ul className="absolute z-10 mt-1 max-h-48 w-full overflow-auto rounded-md border border-gray-300 bg-white py-1 text-sm shadow-lg">
              {methodNameSuggestions.map((m) => (
                <li
                  key={m}
                  className="cursor-pointer px-3 py-1.5 hover:bg-blue-50"
                  onMouseDown={(e) => {
                    e.preventDefault()
                    setConfig({ ...config, methodName: m })
                    setShowMethodSuggestions(false)
                    if (config.className && m && m !== lastSigMethodName) {
                      fetchMethodSignatures(config.className, m)
                    } else {
                      setLastSigMethodName(m)
                    }
                  }}
                >
                  {m}
                </li>
              ))}
            </ul>
          )}
        </div>
      </GtFormGroup>

          <GtFormGroup
            label="Class annotation"
            layout="stack"
            compact
            controlClassName="max-w-none"
            helpText="Optional: only classes with this annotation."
          >
            <Input
              value={config.classAnnotation}
              onChange={(e) => setConfig({ ...config, classAnnotation: e.target.value })}
              disabled={!canEdit}
            />
          </GtFormGroup>

          <GtFormGroup
            label="Method annotation"
            layout="stack"
            compact
            controlClassName="max-w-none"
            helpText="Optional: only methods with this annotation."
          >
            <Input
              value={config.methodAnnotation}
              onChange={(e) => setConfig({ ...config, methodAnnotation: e.target.value })}
              disabled={!canEdit}
            />
          </GtFormGroup>

          <GtFormGroup
            label="Sub type restriction"
            layout="stack"
            compact
            controlClassName="max-w-none"
            helpText="Optional: only subtypes of this class."
          >
            <Input
              value={config.subTypeRestriction}
              onChange={(e) => setConfig({ ...config, subTypeRestriction: e.target.value })}
              disabled={!canEdit}
            />
          </GtFormGroup>

          <GtFormGroup
            label="Super type restriction"
            layout="stack"
            compact
            controlClassName="max-w-none"
            helpText="Optional: only classes with this supertype."
          >
            <Input
              value={config.superTypeRestriction}
              onChange={(e) => setConfig({ ...config, superTypeRestriction: e.target.value })}
              disabled={!canEdit}
            />
          </GtFormGroup>
        </div>

        {methodSignatures.length > 0 && Boolean(config.methodName) && (
          <GtFormGroup
            label="Method signature"
            layout="stack"
            compact
            controlClassName="max-w-none"
            className="mt-2"
          >
            <select
              className={cn(selectClass, 'max-w-full w-full')}
              value={selectedSigIndex}
              onChange={(e) => handleSignatureChange(Number(e.target.value))}
              disabled={!canEdit}
            >
              {methodSignatures.map((sig, i) => (
                <option key={i} value={i}>
                  {signatureText(sig)}
                  {sig.notAvailable ? ' (not available)' : ''}
                </option>
              ))}
            </select>
          </GtFormGroup>
        )}
      </SettingsSection>

      <SettingsSection title="Capture" className="mt-5 border-t border-gray-200 pt-5">
        <GtFormGroup
          label="Capture kind"
          layout="stack"
          compact
          controlClassName="max-w-xs"
          helpText="Transaction = boundary · Trace entry = timeline · Timer = breakdown · Other = overrides only."
        >
          <select
            className={selectClass}
            value={config.captureKind}
            onChange={(e) => handleCaptureKindChange(e.target.value)}
            disabled={!canEdit}
          >
            <option value="transaction">Transaction</option>
            <option value="trace-entry">Trace entry</option>
            <option value="timer">Timer</option>
            <option value="other">Other</option>
          </select>
        </GtFormGroup>

        {showTransaction && (
          <div className="mt-1 grid gap-x-6 gap-y-1 md:grid-cols-2">
            <GtFormGroup
              label="Transaction type"
              required={showTransaction}
              value={config.transactionType}
              layout="stack"
              compact
              controlClassName="max-w-none"
              helpText="Aggregation / filter bucket."
            >
              <Input
                value={config.transactionType}
                onChange={(e) => setConfig({ ...config, transactionType: e.target.value })}
                disabled={!canEdit}
              />
            </GtFormGroup>

            <GtFormGroup
              label="Transaction name template"
              required={showTransaction}
              value={config.transactionNameTemplate}
              layout="stack"
              compact
              controlClassName="max-w-none"
              helpText="Template ({{0}}, {{this}}, … — wiki)."
            >
              <Input
                value={config.transactionNameTemplate}
                onChange={(e) => setConfig({ ...config, transactionNameTemplate: e.target.value })}
                disabled={!canEdit}
              />
            </GtFormGroup>

            <GtFormGroup
              label="Transaction user template"
              layout="stack"
              compact
              controlClassName="max-w-none"
              helpText="Optional user attribute template."
            >
              <Input
                value={config.transactionUserTemplate}
                onChange={(e) => setConfig({ ...config, transactionUserTemplate: e.target.value })}
                disabled={!canEdit}
              />
            </GtFormGroup>

            <GtFormGroup
              label="Already in transaction"
              layout="stack"
              compact
              controlClassName="max-w-none"
              helpText="Behavior when already inside a transaction."
            >
              <select
                className={cn(selectClass, 'max-w-full w-full')}
                value={config.alreadyInTransactionBehavior || 'capture-trace-entry'}
                onChange={(e) => setConfig({ ...config, alreadyInTransactionBehavior: e.target.value })}
                disabled={!canEdit}
              >
                <option value="capture-trace-entry">Capture trace entry</option>
                <option value="capture-new-transaction">Capture new transaction</option>
                <option value="do-nothing">Do nothing</option>
              </select>
            </GtFormGroup>

            <GtFormGroup label="Transaction outer?" layout="stack" compact controlClassName="max-w-none">
              <label className="flex items-center gap-2 text-sm pt-1">
                <Checkbox
                  checked={config.transactionOuter}
                  onCheckedChange={(c) => setConfig({ ...config, transactionOuter: !!c })}
                  disabled={!canEdit}
                />
                Set as outer transaction
              </label>
            </GtFormGroup>
          </div>
        )}

        {showTraceEntry && (
          <GtFormGroup
            label="Trace entry message template"
            required={showTraceEntry}
            value={config.traceEntryMessageTemplate}
            layout="stack"
            compact
            controlClassName="max-w-none"
            className="mt-1"
            helpText="Template ({{0}}, {{this}}, {{_}}, … — wiki)."
          >
            <Input
              value={config.traceEntryMessageTemplate}
              onChange={(e) => setConfig({ ...config, traceEntryMessageTemplate: e.target.value })}
              disabled={!canEdit}
            />
          </GtFormGroup>
        )}

        {(showTraceEntryStackThreshold || showTraceEntryCaptureSelfNested || showTimer) && (
          <div className="mt-1 grid gap-x-6 gap-y-1 md:grid-cols-2">
            {showTraceEntryStackThreshold && (
              <GtFormGroup
                label="Stack threshold"
                pattern={PATTERNS.integer}
                value={config.traceEntryStackThresholdMillis ?? ''}
                layout="stack"
                compact
                controlClassName="max-w-none"
                helpText="Stack when entry exceeds this. Empty = never."
              >
                <div className="flex items-center gap-2">
                  <Input
                    type="number"
                    value={config.traceEntryStackThresholdMillis ?? ''}
                    onChange={(e) => setConfig({
                      ...config,
                      traceEntryStackThresholdMillis: e.target.value === '' ? null : Number(e.target.value),
                    })}
                    className="w-24"
                    disabled={!canEdit}
                  />
                  <span className="text-sm text-gray-500">ms</span>
                </div>
              </GtFormGroup>
            )}

            {showTraceEntryCaptureSelfNested && (
              <GtFormGroup
                label="Self nesting"
                layout="stack"
                compact
                controlClassName="max-w-none"
                helpText="Allow nested entries under the same rule/timer (can be noisy)."
              >
                <label className="flex items-center gap-2 text-sm pt-1">
                  <Checkbox
                    checked={config.traceEntryCaptureSelfNested}
                    onCheckedChange={(c) => setConfig({ ...config, traceEntryCaptureSelfNested: !!c })}
                    disabled={!canEdit}
                  />
                  Capture self nested entries
                </label>
              </GtFormGroup>
            )}

            {showTimer && (
              <GtFormGroup
                label="Timer name"
                required={showTimer}
                pattern={/^[a-zA-Z0-9 ]*$/}
                value={config.timerName}
                invalidMessage="Only letters, numbers and spaces"
                layout="stack"
                compact
                controlClassName="max-w-none"
                helpText="Shared names aggregate in the breakdown."
              >
                <Input
                  value={config.timerName}
                  onChange={(e) => setConfig({ ...config, timerName: e.target.value })}
                  disabled={!canEdit}
                />
              </GtFormGroup>
            )}
          </div>
        )}
      </SettingsSection>

      <SettingsSection title="Advanced" className="mt-5 border-t border-gray-200 pt-5">
        <div className="grid gap-x-6 gap-y-1 md:grid-cols-2">
          <GtFormGroup
            label="Nesting group"
            layout="stack"
            compact
            controlClassName="max-w-none"
            helpText="Suppress nested entries that share this group."
          >
            <Input
              value={config.nestingGroup}
              onChange={(e) => setConfig({ ...config, nestingGroup: e.target.value })}
              disabled={!canEdit}
            />
          </GtFormGroup>

          <GtFormGroup
            label="Order"
            required
            pattern={PATTERNS.integer}
            value={config.order}
            layout="stack"
            compact
            controlClassName="max-w-none"
            helpText="Higher runs later among matching rules."
          >
            <Input
              type="number"
              value={config.order}
              onChange={(e) => setConfig({ ...config, order: Number(e.target.value) || 0 })}
              className="w-24"
              disabled={!canEdit}
            />
          </GtFormGroup>

          <GtFormGroup
            label="Enabled property"
            layout="stack"
            compact
            controlClassName="max-w-none"
            helpText="Plugin property that toggles this rule."
          >
            <Input
              value={config.enabledProperty}
              onChange={(e) => setConfig({ ...config, enabledProperty: e.target.value })}
              disabled={!canEdit}
            />
          </GtFormGroup>

          <GtFormGroup
            label="Trace entry enabled property"
            layout="stack"
            compact
            controlClassName="max-w-none"
            helpText="Plugin property that toggles entry capture."
          >
            <Input
              value={config.traceEntryEnabledProperty}
              onChange={(e) => setConfig({ ...config, traceEntryEnabledProperty: e.target.value })}
              disabled={!canEdit}
            />
          </GtFormGroup>
        </div>
      </SettingsSection>

      {!showTransaction && (
        <SettingsSection title="Optional overrides" className="mt-5 border-t border-gray-200 pt-5">
          <button
            type="button"
            className="text-sm font-medium text-blue-600 hover:underline"
            onClick={() => setShowTransactionOverrides((v) => !v)}
          >
            {showTransactionOverrides ? 'Hide' : 'Show'} transaction overrides
          </button>
          {showTransactionOverrides && (
            <div className="mt-2 grid gap-x-6 gap-y-1 md:grid-cols-2">
              <GtFormGroup
                label="Transaction type"
                layout="stack"
                compact
                controlClassName="max-w-none"
                helpText="Override type on the containing transaction."
              >
                <Input
                  value={config.transactionType}
                  onChange={(e) => setConfig({ ...config, transactionType: e.target.value })}
                  disabled={!canEdit}
                />
              </GtFormGroup>
              <GtFormGroup
                label="Transaction name"
                layout="stack"
                compact
                controlClassName="max-w-none"
                helpText="Override name template on the containing transaction."
              >
                <Input
                  value={config.transactionNameTemplate}
                  onChange={(e) => setConfig({ ...config, transactionNameTemplate: e.target.value })}
                  disabled={!canEdit}
                />
              </GtFormGroup>
              <GtFormGroup
                label="Slow trace threshold"
                pattern={PATTERNS.integer}
                value={config.transactionSlowThresholdMillis ?? ''}
                layout="stack"
                compact
                controlClassName="max-w-none"
                helpText="Override for containing transaction (smallest wins)."
              >
                <div className="flex items-center gap-2">
                  <Input
                    type="number"
                    value={config.transactionSlowThresholdMillis ?? ''}
                    onChange={(e) =>
                      setConfig({
                        ...config,
                        transactionSlowThresholdMillis:
                          e.target.value === '' ? null : parseInt(e.target.value, 10),
                      })
                    }
                    className="w-24"
                    disabled={!canEdit}
                  />
                  <span className="text-sm text-gray-500">ms</span>
                </div>
              </GtFormGroup>
            </div>
          )}
        </SettingsSection>
      )}
      {canEdit ? (
        <GtButtonGroup
          onSave={handleSave}
          hasChanges={!!hasChanges}
          saveLabel={isNew ? 'Add' : 'Save changes'}
          onDelete={!isNew && config.version ? handleDelete : undefined}
          showDelete={!isNew && !!config.version}
          deleteConfirmHeader="Delete instrumentation"
          deleteConfirmMessage="Are you sure you want to delete this instrumentation?"
        >
          <button
            type="button"
            onClick={() => setShowExportModal(true)}
            className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
          >
            Export to JSON
          </button>
        </GtButtonGroup>
      ) : (
        <div className="pt-4">
          <button
            type="button"
            onClick={() => setShowExportModal(true)}
            className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
          >
            Export to JSON
          </button>
        </div>
      )}
      <div className="mt-6 text-xs text-gray-500">
        For more information and configuration details, see the{' '}
        <a
          href="https://github.com/glowroot/glowroot/wiki/Instrumentation"
          target="_blank"
          rel="noopener noreferrer"
          className="text-blue-600 hover:underline"
        >
          Glowroot wiki
        </a>
        .
      </div>
      {showExportModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
          <div className="w-full max-w-2xl rounded-lg bg-white p-6 shadow-xl">
            <h3 className="mb-3 text-lg font-medium">Instrumentation JSON</h3>
            <textarea
              readOnly
              rows={16}
              className="w-full rounded-md border border-gray-300 bg-gray-50 p-3 font-mono text-xs"
              value={(() => {
                const clean: Record<string, unknown> = { ...config }
                delete clean.version
                if (!clean.methodDeclaringClassName) delete clean.methodDeclaringClassName
                for (const key of Object.keys(clean)) {
                  if (key === 'methodParameterTypes') continue
                  const val = clean[key]
                  if (
                    val === '' ||
                    val === null ||
                    val === undefined ||
                    val === false ||
                    (key === 'order' && val === 0)
                  ) {
                    delete clean[key]
                  } else if (Array.isArray(val) && val.length === 0) {
                    delete clean[key]
                  } else if (
                    typeof val === 'object' &&
                    !Array.isArray(val) &&
                    Object.keys(val as Record<string, unknown>).length === 0
                  ) {
                    delete clean[key]
                  }
                }
                return JSON.stringify(clean, null, 2)
              })()}
            />
            <div className="mt-4 flex justify-end">
              <button
                type="button"
                onClick={() => setShowExportModal(false)}
                className="rounded-md bg-gray-900 px-4 py-2 text-sm font-medium text-white hover:bg-gray-800"
              >
                Close
              </button>
            </div>
          </div>
        </div>
      )}
    </SettingsPage>
  )
}
