import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useAgent } from '../../contexts/AgentContext'
import { apiGet, apiPost } from '../../lib/api'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'
import { Button } from '../../components/ui/button'
import { Dialog, DialogTitle, DialogFooter } from '../../components/ui/dialog'

interface InstrumentationConfig {
  className: string
  methodName: string
  captureKind: string
  version: string
}

interface InstrumentationListData {
  configs: InstrumentationConfig[]
  jvmOutOfSync: boolean
  jvmRetransformClassesSupported: boolean
}

function captureKindDisplay(captureKind: string): string {
  switch (captureKind) {
    case 'timer': return 'Timer'
    case 'trace-entry': return 'Trace entry'
    case 'transaction': return 'Transaction'
    default: return 'Other'
  }
}

/** Clean config for export — remove version and fields with default values */
function cleanForExport(config: InstrumentationConfig): Record<string, unknown> {
  const cleaned: Record<string, unknown> = { ...config }
  delete cleaned.version
  // Follow instrumentation-export.js: preserve methodParameterTypes (even [])
  if (!cleaned.methodModifiers || (Array.isArray(cleaned.methodModifiers) && cleaned.methodModifiers.length === 0)) {
    delete cleaned.methodModifiers
  }
  if (
    !cleaned.transactionAttributeTemplates ||
    (typeof cleaned.transactionAttributeTemplates === 'object' &&
      !Array.isArray(cleaned.transactionAttributeTemplates) &&
      Object.keys(cleaned.transactionAttributeTemplates as object).length === 0)
  ) {
    delete cleaned.transactionAttributeTemplates
  }
  for (const [key, value] of Object.entries(cleaned)) {
    if (key === 'methodParameterTypes') continue
    if (value === '' || value === false || value === 0 || value === null) {
      delete cleaned[key]
    }
  }
  return cleaned
}

export function ConfigInstrumentationListPage() {
  const { agentId, agentRollup } = useAgent()
  const [data, setData] = useState<InstrumentationListData | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)
  const [showImport, setShowImport] = useState(false)
  const [showExport, setShowExport] = useState(false)
  const [jsonToImport, setJsonToImport] = useState('')
  const [importError, setImportError] = useState('')
  const [importing, setImporting] = useState(false)
  const [retransforming, setRetransforming] = useState(false)
  const [retransformMessage, setRetransformMessage] = useState('')
  const [showDeleteAllConfirm, setShowDeleteAllConfirm] = useState(false)
  const [deletingAll, setDeletingAll] = useState(false)

  const canEdit = agentRollup?.permissions?.config?.edit?.instrumentation ?? false

  const load = useCallback(async () => {
    if (agentId === null) return
    try {
      const resp = await apiGet<InstrumentationListData>('/backend/config/instrumentation', {
        'agent-id': agentId,
      })
      setData(resp)
      // Preload cache for class name and method name auto completion
      apiGet('/backend/config/preload-classpath-cache', { 'agent-id': agentId }).catch(() => {})
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
    }
  }, [agentId])

  useEffect(() => { load() }, [load])

  async function handleImport() {
    if (agentId === null) return
    setImportError('')
    let configs: unknown
    try {
      configs = JSON.parse(jsonToImport)
    } catch {
      setImportError('Invalid JSON')
      return
    }
    if (!Array.isArray(configs)) {
      configs = [configs]
    }
    const errors: string[] = []
    const postConfigs: unknown[] = []
    for (const config of configs as Record<string, unknown>[]) {
      if (!config.className) errors.push('Missing className')
      if (!config.methodName) errors.push('Missing methodName')
      if (!config.captureKind) {
        errors.push('Missing captureKind')
      } else if (
        typeof config.captureKind !== 'string' ||
        !['timer', 'trace-entry', 'transaction', 'other'].includes(config.captureKind)
      ) {
        errors.push('Invalid captureKind')
      }
      if (errors.length) break
      postConfigs.push({
        classAnnotation: '',
        subTypeRestriction: '',
        superTypeRestriction: '',
        methodAnnotation: '',
        methodReturnType: '',
        nestingGroup: '',
        order: 0,
        transactionType: '',
        transactionNameTemplate: '',
        transactionUserTemplate: '',
        transactionOuter: false,
        traceEntryMessageTemplate: '',
        traceEntryCaptureSelfNested: false,
        timerName: '',
        enabledProperty: '',
        traceEntryEnabledProperty: '',
        ...config,
      })
    }
    if (errors.length) {
      setImportError(errors.join(', '))
      return
    }
    setImporting(true)
    try {
      await apiPost(
        '/backend/config/instrumentation/import?agent-id=' + encodeURIComponent(agentId),
        { configs: postConfigs }
      )
      setShowImport(false)
      setJsonToImport('')
      await load()
    } catch (e) {
      setImportError(e instanceof Error ? e.message : 'Import failed')
    } finally {
      setImporting(false)
    }
  }

  async function handleDeleteAll() {
    if (agentId === null || !data) return
    setDeletingAll(true)
    try {
      const versions = data.configs.map((c) => c.version)
      await apiPost(
        '/backend/config/instrumentation/remove?agent-id=' + encodeURIComponent(agentId),
        { versions }
      )
      setShowDeleteAllConfirm(false)
      await load()
    } finally {
      setDeletingAll(false)
    }
  }

  async function handleRetransform() {
    if (agentId === null) return
    setRetransforming(true)
    setRetransformMessage('')
    try {
      const resp = await apiPost<{ classes?: number }>(
        '/backend/config/reweave?agent-id=' + encodeURIComponent(agentId)
      )
      setData((prev) => prev ? { ...prev, jvmOutOfSync: false } : prev)
      if (resp.classes) {
        setRetransformMessage(`Success (re-transformed ${resp.classes} class${resp.classes > 1 ? 'es' : ''})`)
      } else {
        setRetransformMessage('Success (no classes needed re-transforming)')
      }
    } catch (e) {
      setRetransformMessage(e instanceof Error ? e.message : 'Failed')
    } finally {
      setRetransforming(false)
    }
  }

  if (loading) return <PageSpinner />
  if (error) return <HttpError error={error} />
  if (!data) return null

  const jsonExport = JSON.stringify(data.configs.map(cleanForExport), null, 2)

  return (
    <div>
      {data.jvmOutOfSync && (
        <div className="mb-4 flex items-center gap-3 rounded-md border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
          <span>
            {data.jvmRetransformClassesSupported
              ? 'Instrumentation has been updated but not yet applied to the running JVM.'
              : 'Instrumentation has been updated, but JVM does not support retransforming classes (restart JVM to apply).'}
          </span>
          {data.jvmRetransformClassesSupported && canEdit && (
            <Button size="sm" onClick={handleRetransform} loading={retransforming}>
              Apply changes
            </Button>
          )}
          {retransformMessage && (
            <span className="text-sm text-green-700">{retransformMessage}</span>
          )}
        </div>
      )}

      <div className="rounded-lg border bg-white">
        <div className="border-b px-4 py-3">
          <h2 className="text-lg font-semibold text-gray-900">Instrumentation</h2>
        </div>
        {data.configs.map((config) => (
          <Link
            key={config.version}
            to={`/modern/config/instrumentation?agent-id=${encodeURIComponent(agentId!)}&v=${encodeURIComponent(config.version)}`}
            className="flex items-center justify-between px-4 py-3 text-sm text-gray-900 border-b last:border-b-0 no-underline hover:bg-gray-50"
          >
            <span>{config.className}::{config.methodName}</span>
            <span className="text-xs text-gray-500">{captureKindDisplay(config.captureKind)}</span>
          </Link>
        ))}
        {data.configs.length === 0 && (
          <p className="px-4 py-3 text-sm text-gray-500">No custom instrumentation configured.</p>
        )}
      </div>

      {canEdit && (
        <div className="mt-4 flex items-center gap-3">
          <Link
            to={`/modern/config/instrumentation?agent-id=${encodeURIComponent(agentId!)}&new`}
            className="inline-flex items-center rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 no-underline"
          >
            Add new
          </Link>
          <Button variant="outline" size="sm" onClick={() => setShowImport(true)}>
            Import
          </Button>
          {data.configs.length > 0 && (
            <>
              <Button variant="outline" size="sm" onClick={() => setShowExport(true)}>
                Export
              </Button>
              <Button variant="outline" size="sm" onClick={() => setShowDeleteAllConfirm(true)}>
                Delete all
              </Button>
            </>
          )}
        </div>
      )}

      {/* Import Modal */}
      {showImport && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
          <div className="w-full max-w-2xl rounded-lg bg-white p-6 shadow-xl">
            <h3 className="text-lg font-semibold mb-4">Import instrumentation</h3>
            <textarea
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm font-mono"
              rows={15}
              value={jsonToImport}
              onChange={(e) => { setJsonToImport(e.target.value); setImportError('') }}
              placeholder="Paste JSON here..."
            />
            {importError && (
              <p className="mt-2 text-sm text-red-600">{importError}</p>
            )}
            <div className="mt-4 flex justify-end gap-3">
              <Button variant="outline" onClick={() => setShowImport(false)}>Cancel</Button>
              <Button onClick={handleImport} loading={importing}>Import</Button>
            </div>
          </div>
        </div>
      )}

      {/* Export Modal */}
      {showExport && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
          <div className="w-full max-w-2xl rounded-lg bg-white p-6 shadow-xl">
            <h3 className="text-lg font-semibold mb-4">Export instrumentation</h3>
            <textarea
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm font-mono"
              rows={15}
              value={jsonExport}
              readOnly
            />
            <div className="mt-4 flex justify-end gap-3">
              <Button
                variant="outline"
                onClick={() => {
                  navigator.clipboard.writeText(jsonExport)
                }}
              >
                Copy to clipboard
              </Button>
              <Button variant="outline" onClick={() => setShowExport(false)}>Close</Button>
            </div>
          </div>
        </div>
      )}

      <Dialog
        open={showDeleteAllConfirm}
        onClose={() => {
          if (!deletingAll) setShowDeleteAllConfirm(false)
        }}
      >
        <DialogTitle>Delete all instrumentation</DialogTitle>
        <p className="mt-2 text-sm text-gray-600">
          Are you sure you want to delete all instrumentation?
        </p>
        <DialogFooter>
          <Button
            variant="outline"
            onClick={() => setShowDeleteAllConfirm(false)}
            disabled={deletingAll}
          >
            Cancel
          </Button>
          <Button
            variant="destructive"
            onClick={handleDeleteAll}
            loading={deletingAll}
          >
            Delete
          </Button>
        </DialogFooter>
      </Dialog>
    </div>
  )
}
