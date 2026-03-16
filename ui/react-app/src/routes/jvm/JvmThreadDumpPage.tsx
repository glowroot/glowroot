import { useCallback, useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useAgent } from '../../contexts/AgentContext'
import { apiGet } from '../../lib/api'
import { formatMillis } from '../../lib/formatting'
import { Button } from '../../components/ui/button'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'
import { TraceModal } from '../../components/trace/TraceModal'

interface ThreadInfo {
  name: string
  id: number
  state: string
  stackTraceElements: string[]
}

interface DeadlockedThread {
  name: string
  desc1: string
  desc2: string
}

interface TransactionThread {
  headline?: string
  transactionType?: string
  transactionName?: string
  traceId?: string
  durationNanos?: number
  cpuNanos?: number
  threads: ThreadInfo[]
}

interface ThreadDumpData {
  agentNotConnected?: boolean
  deadlockedCycles?: DeadlockedThread[][]
  transactions?: TransactionThread[]
  unmatchedThreadsByStackTrace?: ThreadInfo[][]
  threadDumpingThread?: ThreadInfo
}

function nanosToMillis(nanos: number): string {
  return formatMillis(nanos / 1e6)
}

function ThreadBlock({ thread }: { thread: ThreadInfo }) {
  const stateClass = thread.state === 'BLOCKED'
    ? 'text-red-600'
    : thread.state === 'WAITING' || thread.state === 'TIMED_WAITING'
      ? 'text-amber-600'
      : ''
  return (
    <div className="mb-1">
      <span>"{thread.name}" #{thread.id}</span>
      {'\n'}
      <span className={stateClass}>
        {'   java.lang.Thread.State: ' + thread.state}
      </span>
      {'\n'}
      {thread.stackTraceElements.map((el, i) => (
        <span key={i}>{'        ' + el + '\n'}</span>
      ))}
    </div>
  )
}

export function JvmThreadDumpPage() {
  const { agentId } = useAgent()
  const [searchParams, setSearchParams] = useSearchParams()
  const modalTraceId = searchParams.get('modal-trace-id')
  const modalCheckLiveTraces = searchParams.get('modal-check-live-traces') === 'true'
  const [data, setData] = useState<ThreadDumpData | null>(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [refreshMessage, setRefreshMessage] = useState<string | null>(null)
  const [error, setError] = useState<unknown>(null)

  const load = useCallback(async (isRefresh = false) => {
    if (agentId === null) return
    if (isRefresh) {
      setRefreshing(true)
      setRefreshMessage(null)
    } else {
      setLoading(true)
    }
    try {
      const resp = await apiGet<ThreadDumpData>('/backend/jvm/thread-dump', {
        'agent-id': agentId,
      })
      setData(resp)
      setError(null)
      if (isRefresh) {
        setRefreshMessage('Refreshed')
      }
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
      setRefreshing(false)
    }
  }, [agentId])

  useEffect(() => { load(false) }, [load])

  function exportAsText() {
    if (!data) return
    const lines: string[] = []
    if (data.deadlockedCycles) {
      for (const cycle of data.deadlockedCycles) {
        lines.push('Found deadlock:')
        for (const t of cycle) {
          lines.push(`"${t.name}"`)
          lines.push(`  ${t.desc1}`)
          lines.push(`  ${t.desc2}`)
        }
        lines.push('')
      }
    }
    if (data.transactions) {
      lines.push('Matched threads (matched to currently executing transactions):')
      for (const tx of data.transactions) {
        if (tx.transactionType) {
          lines.push(tx.headline || '')
          lines.push('Transaction type: ' + tx.transactionType)
          lines.push('Transaction name: ' + (tx.transactionName || ''))
          if (tx.durationNanos !== undefined) {
            lines.push('Duration: ' + nanosToMillis(tx.durationNanos) + ' milliseconds')
          }
          if (tx.cpuNanos !== undefined && tx.cpuNanos !== -1) {
            lines.push('CPU time: ' + nanosToMillis(tx.cpuNanos) + ' milliseconds')
          }
        }
        for (const t of tx.threads) {
          lines.push(`"${t.name}" #${t.id}`)
          lines.push('   java.lang.Thread.State: ' + t.state)
          for (const el of t.stackTraceElements) lines.push('        ' + el)
        }
        lines.push('')
      }
    }
    if (data.unmatchedThreadsByStackTrace) {
      lines.push('Unmatched threads:')
      for (const group of data.unmatchedThreadsByStackTrace) {
        for (const t of group) {
          lines.push(`"${t.name}" #${t.id}`)
          lines.push('   java.lang.Thread.State: ' + t.state)
          for (const el of t.stackTraceElements) lines.push('        ' + el)
        }
        lines.push('----------------------------------------')
      }
    }
    if (data.threadDumpingThread) {
      const t = data.threadDumpingThread
      lines.push('Thread dumping thread (producing this thread dump):')
      lines.push(`"${t.name}" #${t.id}`)
      lines.push('   java.lang.Thread.State: ' + t.state)
      for (const el of t.stackTraceElements) lines.push('        ' + el)
    }
    const blob = new Blob([lines.join('\n')], { type: 'text/plain' })
    const url = URL.createObjectURL(blob)
    window.open(url)
  }

  if (loading) return <PageSpinner />
  if (error) return <HttpError error={error} />
  if (!data) return null

  if (data.agentNotConnected) {
    return (
      <div className="rounded-md bg-red-50 border border-red-200 px-4 py-3 text-sm text-red-700">
        This feature is only available when the agent is running and connected
      </div>
    )
  }

  return (
    <div>
      <div className="mb-4 flex items-center gap-2">
        <Button variant="outline" size="sm" loading={refreshing} onClick={() => load(true)}>
          Refresh
        </Button>
        <Button variant="outline" size="sm" onClick={exportAsText}>Export as text</Button>
        {refreshMessage && <span className="text-sm text-green-600">{refreshMessage}</span>}
      </div>
      <pre className="rounded-lg border bg-white p-4 text-xs font-mono whitespace-pre-wrap overflow-auto max-h-[80vh]">
        {data.deadlockedCycles && data.deadlockedCycles.length > 0 && (
          <>
            {data.deadlockedCycles.map((cycle, i) => (
              <span key={i}>
                <strong className="underline">Found deadlock:</strong>
                {'\n'}
                {cycle.map((t, j) => (
                  <span key={j}>
                    "{t.name}"{'\n'}
                    {'  '}{t.desc1}{'\n'}
                    {'  '}{t.desc2}{'\n'}
                  </span>
                ))}
                {'\n'}
              </span>
            ))}
          </>
        )}

        {data.transactions && data.transactions.length > 0 && (
          <>
            <strong className="underline">Matched threads</strong>
            {' (matched to currently executing transactions):\n'}
            {data.transactions.map((tx, i) => (
              <span key={i}>
                {tx.transactionType && (
                  <>
                    <strong>{tx.headline}</strong>
                    {tx.traceId && (
                      <>
                        {'  '}
                        <button
                          type="button"
                          onClick={() => {
                            setSearchParams((prev) => {
                              const next = new URLSearchParams(prev)
                              next.set('modal-trace-id', tx.traceId!)
                              next.set('modal-check-live-traces', 'true')
                              return next
                            })
                          }}
                          className="font-sans text-xs font-normal text-blue-600 hover:underline cursor-pointer"
                        >
                          view trace
                        </button>
                      </>
                    )}
                    {'\n'}
                    <strong>Transaction type: </strong>{tx.transactionType}{'\n'}
                    <strong>Transaction name:</strong> {tx.transactionName}{'\n'}
                    <strong>Duration:</strong>{' '}
                    {tx.durationNanos !== undefined ? nanosToMillis(tx.durationNanos) : '?'} milliseconds{'\n'}
                    {tx.cpuNanos !== undefined && tx.cpuNanos !== -1 && (
                      <><strong>CPU time:</strong> {nanosToMillis(tx.cpuNanos)} milliseconds{'\n'}</>
                    )}
                  </>
                )}
                {tx.threads.map((t, j) => <ThreadBlock key={j} thread={t} />)}
                {'\n'}
              </span>
            ))}
          </>
        )}

        {data.unmatchedThreadsByStackTrace && data.unmatchedThreadsByStackTrace.length > 0 && (
          <>
            <strong className="underline">Unmatched threads</strong>
            {' (this may possibly include currently executing transactions that just started or just ended):\n'}
            {data.unmatchedThreadsByStackTrace.map((group, i) => (
              <span key={i}>
                {group.map((t, j) => <ThreadBlock key={j} thread={t} />)}
                {i < data.unmatchedThreadsByStackTrace!.length - 1 && '----------------------------------------\n'}
              </span>
            ))}
          </>
        )}

        {data.threadDumpingThread && (
          <>
            <strong className="underline">Thread dumping thread</strong>
            {' (producing this thread dump):\n'}
            <ThreadBlock thread={data.threadDumpingThread} />
          </>
        )}
      </pre>
      <TraceModal
        agentId={agentId || ''}
        traceId={modalTraceId}
        checkLiveTraces={modalCheckLiveTraces}
        onClose={() => {
          setSearchParams((prev) => {
            const next = new URLSearchParams(prev)
            next.delete('modal-trace-id')
            next.delete('modal-check-live-traces')
            return next
          })
        }}
      />
    </div>
  )
}
