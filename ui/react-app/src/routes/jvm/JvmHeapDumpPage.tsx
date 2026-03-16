import { useCallback, useEffect, useState } from 'react'
import { useAgent } from '../../contexts/AgentContext'
import { apiGet, apiPost } from '../../lib/api'
import { formatBytes } from '../../lib/formatting'
import { Input } from '../../components/ui/input'
import { Button } from '../../components/ui/button'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'

interface HeapDumpDefaultDirData {
  agentNotConnected?: boolean
  directory: string
}

interface HeapDumpResponse {
  error?: string
  directoryDoesNotExist?: boolean
  filePath?: string
  fileSizeBytes?: number
}

export function JvmHeapDumpPage() {
  const { agentId } = useAgent()
  const [directory, setDirectory] = useState('')
  const [agentNotConnected, setAgentNotConnected] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)

  const [checkingDisk, setCheckingDisk] = useState(false)
  const [availableDiskSpace, setAvailableDiskSpace] = useState<number | null>(null)
  const [diskStatus, setDiskStatus] = useState<string | null>(null)
  const [diskError, setDiskError] = useState('')

  const [showConfirmModal, setShowConfirmModal] = useState(false)
  const [dumping, setDumping] = useState(false)
  const [dumpResult, setDumpResult] = useState<HeapDumpResponse | null>(null)
  const [dumpStatus, setDumpStatus] = useState<string | null>(null)
  const [dumpError, setDumpError] = useState('')
  const [actionHttpError, setActionHttpError] = useState<unknown>(null)

  const load = useCallback(async () => {
    if (agentId === null) return
    try {
      const resp = await apiGet<HeapDumpDefaultDirData>('/backend/jvm/heap-dump-default-dir', {
        'agent-id': agentId,
      })
      setAgentNotConnected(!!resp.agentNotConnected)
      setDirectory(resp.directory || '')
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
    }
  }, [agentId])

  useEffect(() => { load() }, [load])

  async function handleCheckDiskSpace() {
    if (agentId === null) return
    setCheckingDisk(true)
    setDiskError('')
    setDumpError('')
    setDiskStatus(null)
    setDumpStatus(null)
    setAvailableDiskSpace(null)
    setDumpResult(null)
    setActionHttpError(null)
    try {
      const resp = await apiPost<number | { error?: string; directoryDoesNotExist?: boolean }>(
        '/backend/jvm/available-disk-space?agent-id=' + encodeURIComponent(agentId),
        { directory }
      )
      if (typeof resp === 'object' && resp !== null && resp.error) {
        setDiskError(resp.error)
      } else if (typeof resp === 'object' && resp !== null && resp.directoryDoesNotExist) {
        setDiskError('Directory does not exist')
      } else if (typeof resp === 'number') {
        setAvailableDiskSpace(resp)
        setDiskStatus('See disk space below')
      }
    } catch (e) {
      setActionHttpError(e)
    } finally {
      setCheckingDisk(false)
    }
  }

  async function handleHeapDump() {
    if (agentId === null) return
    setShowConfirmModal(false)
    setDumping(true)
    setDiskError('')
    setDumpError('')
    setDiskStatus(null)
    setDumpStatus(null)
    setDumpResult(null)
    setAvailableDiskSpace(null)
    setActionHttpError(null)
    try {
      const resp = await apiPost<HeapDumpResponse>(
        '/backend/jvm/heap-dump?agent-id=' + encodeURIComponent(agentId),
        { directory }
      )
      if (resp.directoryDoesNotExist) {
        setDumpError('Directory does not exist')
      } else if (resp.error) {
        setDumpError(resp.error)
      } else {
        setDumpResult(resp)
        setDumpStatus('Heap dump created')
      }
    } catch (e) {
      setActionHttpError(e)
    } finally {
      setDumping(false)
    }
  }

  if (loading) return <PageSpinner />
  if (error) return <HttpError error={error} />

  if (agentNotConnected) {
    return (
      <div className="rounded-md bg-red-50 border border-red-200 px-4 py-3 text-sm text-red-700">
        This feature is only available when the agent is running and connected
      </div>
    )
  }

  return (
    <div className="rounded-lg border bg-white p-6 space-y-4">
      <div>
        <label htmlFor="directory" className="block text-sm font-medium text-gray-700 mb-1">
          Directory
        </label>
        <Input
          id="directory"
          autoFocus
          value={directory}
          onChange={(e) => setDirectory(e.target.value)}
          className="max-w-lg"
        />
        <p className="mt-1 text-xs text-gray-500">
          Use the jvm arg{' '}
          <code className="whitespace-nowrap">-XX:HeapDumpPath=&lt;directory&gt;</code>{' '}
          to change the default heap dump location.
        </p>
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <Button
          variant="destructive"
          onClick={() => setShowConfirmModal(true)}
          loading={dumping}
        >
          Heap dump
        </Button>
        <Button variant="outline" onClick={handleCheckDiskSpace} loading={checkingDisk}>
          Check disk space
        </Button>
        {dumpStatus && <span className="text-sm text-green-600">{dumpStatus}</span>}
        {diskStatus && <span className="text-sm text-green-600">{diskStatus}</span>}
      </div>
      {availableDiskSpace !== null && (
        <div className="rounded-md bg-gray-50 border p-3 text-sm text-gray-700">
          Free disk space: <strong>{formatBytes(availableDiskSpace)}</strong>
        </div>
      )}
      {diskError && <p className="text-sm text-red-600">{diskError}</p>}
      {dumpResult && (
        <div className="rounded-md bg-gray-50 border p-3 text-sm text-gray-700 space-y-1">
          {dumpResult.filePath && (
            <div>
              Heap dump: <strong className="break-all">{dumpResult.filePath}</strong>
            </div>
          )}
          {dumpResult.fileSizeBytes !== undefined && (
            <div>
              File size: <strong>{formatBytes(dumpResult.fileSizeBytes)}</strong>
            </div>
          )}
        </div>
      )}
      {dumpError && <p className="text-sm text-red-600">{dumpError}</p>}
      {actionHttpError != null && <HttpError error={actionHttpError} />}

      {showConfirmModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
          <div className="w-full max-w-md rounded-lg bg-white p-6 shadow-xl">
            <h3 className="mb-2 text-lg font-medium text-gray-900">Perform heap dump?</h3>
            <p className="mb-4 text-sm text-gray-600">
              Note: This may hang the JVM for a bit while the heap dump is generated.
            </p>
            <div className="flex justify-end gap-2">
              <Button variant="outline" onClick={() => setShowConfirmModal(false)}>
                Cancel
              </Button>
              <Button variant="destructive" onClick={handleHeapDump}>
                Yes
              </Button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
