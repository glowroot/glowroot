import { useCallback, useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useAgent } from '../../contexts/AgentContext'
import { apiPost } from '../../lib/api'
import { Input } from '../../components/ui/input'
import { Button } from '../../components/ui/button'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'

interface HistogramItem {
  className: string
  bytes: number
  count: number
}

interface HeapHistogramData {
  agentNotConnected?: boolean
  agentUnsupportedOperation?: string | false
  unavailableDueToRunningInJre?: boolean
  unavailableDueToRunningInJ9Jvm?: boolean
  unavailableDueToDockerAlpinePidOne?: boolean
  items: HistogramItem[]
  totalBytes: number
  totalCount: number
}

function formatNumber(n: number): string {
  return n.toLocaleString()
}

type SortAttr = 'class-name' | 'bytes' | 'count'
type SortDir = 'asc' | 'desc'

export function JvmHeapHistogramPage() {
  const { agentId } = useAgent()
  const [searchParams, setSearchParams] = useSearchParams()
  const [data, setData] = useState<HeapHistogramData | null>(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [refreshMessage, setRefreshMessage] = useState<string | null>(null)
  const [error, setError] = useState<unknown>(null)

  const filterComparator = (searchParams.get('filter-comparator') as 'contains' | 'begins' | 'ends') || 'contains'
  const filterValue = searchParams.get('filter-value') || ''
  const filterLimit = Number(searchParams.get('filter-limit')) || 200
  const rawSortAttr = searchParams.get('sort-attribute')
  const sortAttr: SortAttr =
    rawSortAttr === 'class-name' || rawSortAttr === 'count' ? rawSortAttr : 'bytes'
  const sortDirParam = searchParams.get('sort-direction')
  const sortDir: SortDir =
    sortAttr === 'class-name'
      ? sortDirParam === 'desc' ? 'desc' : 'asc'
      : sortDirParam === 'asc' ? 'asc' : 'desc'

  function updateQueryParams(updates: Record<string, string | null>) {
    const next = new URLSearchParams(searchParams)
    for (const [k, v] of Object.entries(updates)) {
      if (v === null) next.delete(k)
      else next.set(k, v)
    }
    setSearchParams(next, { replace: true })
  }

  const load = useCallback(async (isRefresh = false) => {
    if (agentId === null) return
    if (isRefresh) {
      setRefreshing(true)
      setRefreshMessage(null)
    } else {
      setLoading(true)
    }
    try {
      const resp = await apiPost<HeapHistogramData>(
        '/backend/jvm/heap-histogram?agent-id=' + encodeURIComponent(agentId)
      )
      setData(resp)
      setError(null)
      if (isRefresh) {
        setRefreshMessage('Complete')
      }
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
      setRefreshing(false)
    }
  }, [agentId])

  useEffect(() => { load(false) }, [load])

  const { displayedItems, limitApplied, filteredItems, filteredTotalBytes, filteredTotalCount, maxBytes } =
    useMemo(() => {
      if (!data?.items) {
        return {
          displayedItems: [] as HistogramItem[],
          limitApplied: false,
          filteredItems: [] as HistogramItem[],
          filteredTotalBytes: 0,
          filteredTotalCount: 0,
          maxBytes: 1,
        }
      }
      const fv = filterValue.toLowerCase()
      const sorted = [...data.items].sort((a, b) => {
        let cmp: number
        if (sortAttr === 'class-name') {
          const s1 = a.className.toLowerCase()
          const s2 = b.className.toLowerCase()
          cmp = s1 < s2 ? -1 : s1 > s2 ? 1 : 0
        } else {
          cmp = a[sortAttr] - b[sortAttr]
        }
        return sortDir === 'asc' ? cmp : -cmp
      })

      if (!fv) {
        const displayed = sorted.slice(0, filterLimit)
        const max = displayed.reduce((acc, item) => Math.max(acc, item.bytes), 0)
        return {
          displayedItems: displayed,
          limitApplied: sorted.length > filterLimit,
          filteredItems: sorted,
          filteredTotalBytes: data.totalBytes,
          filteredTotalCount: data.totalCount,
          maxBytes: max || 1,
        }
      }

      const matched: HistogramItem[] = []
      let totalBytes = 0
      let totalCount = 0
      let max = 0
      for (const item of sorted) {
        const cn = item.className.toLowerCase()
        const isMatch =
          filterComparator === 'begins'
            ? cn.startsWith(fv)
            : filterComparator === 'ends'
              ? cn.endsWith(fv)
              : cn.includes(fv)
        if (isMatch) {
          matched.push(item)
          if (matched.length <= filterLimit) {
            max = Math.max(max, item.bytes)
          }
          totalBytes += item.bytes
          totalCount += item.count
        }
      }
      return {
        displayedItems: matched.slice(0, filterLimit),
        limitApplied: matched.length > filterLimit,
        filteredItems: matched,
        filteredTotalBytes: totalBytes,
        filteredTotalCount: totalCount,
        maxBytes: max || 1,
      }
    }, [data, filterValue, filterComparator, filterLimit, sortAttr, sortDir])

  function handleSort(attr: SortAttr) {
    let nextDir: SortDir
    if (sortAttr === attr) {
      nextDir = sortDir === 'asc' ? 'desc' : 'asc'
    } else {
      nextDir = attr === 'class-name' ? 'asc' : 'desc'
    }
    const updates: Record<string, string | null> = {}
    if (attr === 'bytes' && nextDir === 'desc') {
      updates['sort-attribute'] = null
      updates['sort-direction'] = null
    } else {
      updates['sort-attribute'] = attr
      const defaultDirForAttr = attr === 'class-name' ? 'asc' : 'desc'
      updates['sort-direction'] = nextDir === defaultDirForAttr ? null : nextDir
    }
    updateQueryParams(updates)
  }

  function ariaSort(attr: SortAttr): 'ascending' | 'descending' | undefined {
    if (sortAttr !== attr) return undefined
    return sortDir === 'asc' ? 'ascending' : 'descending'
  }

  function sortIndicator(attr: SortAttr) {
    if (sortAttr !== attr) return ''
    return sortDir === 'asc' ? ' \u25B2' : ' \u25BC'
  }

  function exportAsCsv() {
    const lines = ['Class name,Bytes,Count']
    for (const item of filteredItems) {
      lines.push(`"${item.className}",${item.bytes},${item.count}`)
    }
    const blob = new Blob([lines.join('\n')], { type: 'text/csv' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = 'heap-histogram.csv'
    a.click()
  }

  function exportAsText() {
    const classWidth = Math.max(
      'Class name'.length,
      ...filteredItems.map((i) => i.className.length)
    )
    const byteStrings = filteredItems.map((i) => i.bytes.toLocaleString())
    const countStrings = filteredItems.map((i) => i.count.toLocaleString())
    const bytesWidth = Math.max('Bytes'.length, ...byteStrings.map((s) => s.length))
    const countWidth = Math.max('Count'.length, ...countStrings.map((s) => s.length))
    const lines = [
      `${'Class name'.padEnd(classWidth)}  ${'Bytes'.padStart(bytesWidth)}  ${'Count'.padStart(countWidth)}`,
    ]
    for (let i = 0; i < filteredItems.length; i++) {
      lines.push(
        `${filteredItems[i].className.padEnd(classWidth)}  ${byteStrings[i].padStart(bytesWidth)}  ${countStrings[i].padStart(countWidth)}`
      )
    }
    const text = lines.join('\n')
    const win = window.open('', '_blank')
    if (win) {
      win.document.title = 'Heap histogram'
      const pre = win.document.createElement('pre')
      pre.style.whiteSpace = 'pre-wrap'
      pre.textContent = text
      win.document.body.appendChild(pre)
      return
    }
    const blob = new Blob([text], { type: 'text/plain' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = 'heap-histogram.txt'
    a.click()
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
  if (data.agentUnsupportedOperation) {
    return (
      <div className="rounded-md bg-amber-50 border border-amber-200 px-4 py-3 text-sm text-amber-700">
        This feature is only supported by Glowroot agent 0.9.2 and later
        (currently reporting agent version is {data.agentUnsupportedOperation})
      </div>
    )
  }
  if (data.unavailableDueToRunningInJre) {
    return (
      <div className="rounded-md bg-amber-50 border border-amber-200 px-4 py-3 text-sm text-amber-700">
        This feature is only available when the agent is running in a JDK (currently running in a JRE).
      </div>
    )
  }
  if (data.unavailableDueToRunningInJ9Jvm) {
    return (
      <div className="rounded-md bg-amber-50 border border-amber-200 px-4 py-3 text-sm text-amber-700">
        This feature is not available when the agent is running in IBM J9 VM / Eclipse OpenJ9 VM.
      </div>
    )
  }
  if (data.unavailableDueToDockerAlpinePidOne) {
    return (
      <div className="rounded-md bg-amber-50 border border-amber-200 px-4 py-3 text-sm text-amber-700">
        This feature is not available when the agent is running in a JVM with PID 1 inside of a Docker Alpine container.{' '}
        See{' '}
        <a
          href="https://github.com/docker-library/openjdk/issues/76"
          target="_blank"
          rel="noopener noreferrer"
          className="underline"
        >
          https://github.com/docker-library/openjdk/issues/76
        </a>{' '}
        for workaround options.
      </div>
    )
  }

  const showProportionBar = sortAttr === 'bytes' && sortDir === 'desc'

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <Button variant="outline" size="sm" loading={refreshing} onClick={() => load(true)}>
          Refresh
        </Button>
        <Button variant="outline" size="sm" onClick={exportAsCsv}>Export as CSV</Button>
        <Button variant="outline" size="sm" onClick={exportAsText}>Export as text</Button>
        {refreshMessage && <span className="text-sm text-green-600">{refreshMessage}</span>}
      </div>

      <div className="flex items-center gap-2 flex-wrap">
        <span className="text-sm text-gray-600">Class name</span>
        <select
          className="rounded-md border border-gray-300 px-2 py-1 text-sm"
          value={filterComparator}
          onChange={(e) => {
            const val = e.target.value
            updateQueryParams({ 'filter-comparator': val === 'contains' ? null : val })
          }}
        >
          <option value="contains">contains</option>
          <option value="begins">begins with</option>
          <option value="ends">ends with</option>
        </select>
        <Input
          value={filterValue}
          onChange={(e) => {
            const val = e.target.value
            updateQueryParams({ 'filter-value': val || null })
          }}
          placeholder="Filter..."
          className="w-48"
        />
        <span className="text-sm text-gray-600 ml-4">Display</span>
        <select
          className="rounded-md border border-gray-300 px-2 py-1 text-sm"
          value={filterLimit}
          onChange={(e) => {
            const val = Number(e.target.value)
            updateQueryParams({ 'filter-limit': val === 200 ? null : String(val) })
          }}
        >
          {[100, 200, 500, 1000, 2000, 5000].map((n) => (
            <option key={n} value={n}>{n.toLocaleString()}</option>
          ))}
        </select>
      </div>

      <div className="rounded-lg border bg-white overflow-auto">
        <table className="min-w-full divide-y divide-gray-200 text-sm">
          <thead className="bg-gray-50">
            <tr>
              <th
                aria-sort={ariaSort('class-name')}
                className="px-4 py-2 text-left font-medium text-gray-700 cursor-pointer select-none"
                onClick={() => handleSort('class-name')}
              >
                Class name{sortIndicator('class-name')}
              </th>
              <th
                aria-sort={ariaSort('bytes')}
                className="px-4 py-2 text-right font-medium text-gray-700 cursor-pointer select-none w-40"
                onClick={() => handleSort('bytes')}
              >
                Bytes{sortIndicator('bytes')}
              </th>
              <th
                aria-sort={ariaSort('count')}
                className="px-4 py-2 text-right font-medium text-gray-700 cursor-pointer select-none w-28"
                onClick={() => handleSort('count')}
              >
                Count{sortIndicator('count')}
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {displayedItems.map((item) => {
              const barPct = (item.bytes / maxBytes) * 100
              return (
                <tr key={item.className}>
                  <td className="px-4 py-1 font-mono text-xs text-gray-900 break-all">
                    {showProportionBar ? (
                      <div className="relative">
                        <div
                          className="absolute inset-y-0 left-0 bg-gray-200 rounded-sm"
                          style={{ width: barPct < 0.1 ? '0' : `${barPct}%` }}
                        />
                        <span className="relative">{item.className}</span>
                      </div>
                    ) : (
                      item.className
                    )}
                  </td>
                  <td className="px-4 py-1 text-right text-gray-700">
                    {formatNumber(item.bytes)}
                  </td>
                  <td className="px-4 py-1 text-right text-gray-700">{formatNumber(item.count)}</td>
                </tr>
              )
            })}
            {limitApplied && (
              <tr>
                <td colSpan={3} className="px-4 py-1.5 text-xs text-gray-600">
                  ... <em>display limit applied</em>
                </td>
              </tr>
            )}
          </tbody>
          <tfoot className="bg-gray-50">
            <tr>
              <td className="px-4 py-2 font-medium text-gray-700">Total</td>
              <td className="px-4 py-2 text-right font-medium text-gray-700">
                {formatNumber(filteredTotalBytes)}
              </td>
              <td className="px-4 py-2 text-right font-medium text-gray-700">
                {formatNumber(filteredTotalCount)}
              </td>
            </tr>
          </tfoot>
        </table>
      </div>
    </div>
  )
}
