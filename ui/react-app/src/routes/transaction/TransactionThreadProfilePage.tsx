/*
 * TransactionThreadProfilePage — interactive profile tree with include/exclude filter.
 *
 * Backend: GET /backend/transaction/profile
 */

import { type ReactNode, useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useTransaction } from '../../contexts/TransactionContext'
import { useAgent } from '../../contexts/AgentContext'
import { useLayout } from '../../contexts/LayoutContext'
import { apiGet } from '../../lib/api'
import { gtParseIncludesExcludes } from '../../lib/parser'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'

interface ProfileNode {
  stackTraceElement: string
  sampleCount: number
  ellipsedSampleCount?: number
  leafThreadState?: string
  childNodes?: ProfileNode[]
}

interface ProfileResponse {
  rootNodes: ProfileNode[]
  unfilteredSampleCount: number
}

interface ProfileData {
  profile: ProfileResponse
  overwritten?: boolean
  hasUnfilteredMainThreadProfile?: boolean
  hasUnfilteredAuxThreadProfile?: boolean
}

export function TransactionThreadProfilePage() {
  const txn = useTransaction()
  const { layout } = useLayout()
  const { agentRollupId } = useAgent()
  const [searchParams, setSearchParams] = useSearchParams()

  const urlAuxiliary = searchParams.get('auxiliary') === 'true'
  const urlFilter = searchParams.get('filter') || ''
  const truncateRaw = searchParams.get('truncate-branch-percentage')
  const truncatePct =
    truncateRaw !== null && truncateRaw !== '' && Number.isFinite(Number(truncateRaw))
      ? Number(truncateRaw)
      : 0.1

  const [auxiliary, setAuxiliary] = useState(urlAuxiliary)
  const [filter, setFilter] = useState(urlFilter)
  const [appliedFilter, setAppliedFilter] = useState(urlFilter)

  useEffect(() => {
    setAuxiliary(urlAuxiliary)
    setFilter(urlFilter)
    setAppliedFilter(urlFilter)
  }, [urlAuxiliary, urlFilter])

  const [profile, setProfile] = useState<ProfileNode | null>(null)
  const [unfilteredSampleCount, setUnfilteredSampleCount] = useState(0)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [overwritten, setOverwritten] = useState(false)
  const [parsingError, setParsingError] = useState('')
  const [hasMain, setHasMain] = useState(false)
  const [hasAux, setHasAux] = useState(false)

  // Expand state
  const [expandedPaths, setExpandedPaths] = useState<Set<string>>(new Set())
  const [showAll, setShowAll] = useState(false)

  const fetchData = useCallback(
    (filterToUse = appliedFilter, auxToUse = auxiliary) => {
      if (agentRollupId === null || !txn.transactionType) return
      let parseResult: { includes: string[]; excludes: string[] }
      try {
        parseResult = gtParseIncludesExcludes(filterToUse)
      } catch (err) {
        setParsingError(err instanceof Error ? err.message : String(err))
        setProfile(null)
        setUnfilteredSampleCount(0)
        setLoading(false)
        return
      }
      setParsingError('')
      setLoading(true)
      setError(null)
      const query: Record<string, string | string[] | number | undefined> = {
        'agent-rollup-id': agentRollupId,
        'transaction-type': txn.transactionType,
        from: Math.floor(txn.range.chartFrom / 60000) * 60000,
        to: Math.ceil(txn.range.chartTo / 60000) * 60000,
        auxiliary: String(auxToUse),
        'truncate-branch-percentage': truncatePct,
      }
      if (txn.transactionName) {
        query['transaction-name'] = txn.transactionName
      }
      if (parseResult.includes.length) {
        query['include'] = parseResult.includes
      }
      if (parseResult.excludes.length) {
        query['exclude'] = parseResult.excludes
      }

      apiGet<ProfileData>('/backend/transaction/profile', query)
        .then((data) => {
          if (data.overwritten) {
            setOverwritten(true)
            setProfile(null)
            return
          }
          setOverwritten(false)
          const mainExists = !!data.hasUnfilteredMainThreadProfile
          const auxExists = !!data.hasUnfilteredAuxThreadProfile
          setHasMain(mainExists)
          setHasAux(auxExists)
          if (!mainExists && auxExists && !auxToUse) {
            setAuxiliary(true)
            fetchData(filterToUse, true)
            return
          }
          if (data.profile && data.profile.unfilteredSampleCount) {
            // Build synthetic root node from rootNodes (matching handlebars-rendering.js)
            const rootNode: ProfileNode = {
              stackTraceElement: '',
              sampleCount: 0,
              ellipsedSampleCount: 0,
              childNodes: [],
            }
            for (const node of data.profile.rootNodes) {
              rootNode.sampleCount += node.sampleCount
              rootNode.ellipsedSampleCount =
                (rootNode.ellipsedSampleCount || 0) + (node.ellipsedSampleCount || 0)
              if (!node.ellipsedSampleCount || node.sampleCount > node.ellipsedSampleCount) {
                rootNode.childNodes!.push(node)
              }
            }
            setProfile(rootNode)
            setUnfilteredSampleCount(data.profile.unfilteredSampleCount)
            // Auto-expand first level
            setExpandedPaths(new Set(['']))
            setShowAll(false)
          } else {
            setProfile(null)
            setUnfilteredSampleCount(0)
          }
        })
        .catch(setError)
        .finally(() => setLoading(false))
    },
    [
      agentRollupId,
      txn.transactionType,
      txn.transactionName,
      txn.range.chartFrom,
      txn.range.chartTo,
      appliedFilter,
      auxiliary,
      truncatePct,
    ]
  )

  useEffect(() => {
    fetchData()
  }, [fetchData, txn.refreshCounter])

  function applyFilterAndRefresh(nextFilter = filter, nextAux = auxiliary) {
    setAppliedFilter(nextFilter)
    setAuxiliary(nextAux)
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev)
      if (nextFilter) {
        next.set('filter', nextFilter)
      } else {
        next.delete('filter')
      }
      if (nextAux) {
        next.set('auxiliary', 'true')
      } else {
        next.delete('auxiliary')
      }
      return next
    })
    fetchData(nextFilter, nextAux)
  }

  const toggleExpand = useCallback((path: string) => {
    setExpandedPaths((prev) => {
      const next = new Set(prev)
      if (next.has(path)) {
        next.delete(path)
      } else {
        next.add(path)
      }
      return next
    })
  }, [])

  function expandAll() {
    if (!profile) return
    const paths = new Set<string>()
    function walk(node: ProfileNode, path: string) {
      paths.add(path)
      if (node.childNodes) {
        for (let i = 0; i < node.childNodes.length; i++) {
          walk(node.childNodes[i], path + '/' + i)
        }
      }
    }
    walk(profile, '')
    setExpandedPaths(paths)
    setShowAll(true)
  }

  // Standalone flame graph link (with explicit rounded from/to, no summary-sort-order)
  const flameParams = new URLSearchParams()
  if (layout.central && agentRollupId) {
    if (searchParams.has('agent-id')) {
      flameParams.set('agent-id', agentRollupId)
    } else {
      flameParams.set('agent-rollup-id', agentRollupId)
    }
  }
  if (txn.transactionType) {
    flameParams.set('transaction-type', txn.transactionType)
  }
  if (txn.transactionName) {
    flameParams.set('transaction-name', txn.transactionName)
  }
  flameParams.set('from', String(Math.round(txn.range.chartFrom)))
  flameParams.set('to', String(Math.round(txn.range.chartTo)))
  if (auxiliary || (!hasMain && hasAux)) {
    flameParams.set('auxiliary', 'true')
  }
  if (appliedFilter) {
    flameParams.set('filter', appliedFilter)
  }
  if (truncatePct !== 0.1) {
    flameParams.set('truncate-branch-percentage', String(truncatePct))
  }
  const flameGraphHref = `/modern/transaction/thread-flame-graph?${flameParams.toString()}`

  const filterIncludes = useMemo(() => {
    try {
      return gtParseIncludesExcludes(appliedFilter)
        .includes.map((s) => s.trim().toLowerCase())
        .filter(Boolean)
    } catch {
      return []
    }
  }, [appliedFilter])

  if (loading && !profile && !parsingError) return <PageSpinner />
  if (error && !profile) return <HttpError error={error} />

  return (
    <div className="space-y-4">
      {/* Main / Auxiliary toggle */}
      <div className="flex gap-4 text-sm items-center">
        <button
          disabled={!hasMain && hasAux}
          className={
            !auxiliary
              ? 'font-medium text-blue-600 disabled:opacity-50 disabled:cursor-not-allowed'
              : 'text-gray-500 hover:text-gray-700 disabled:opacity-50 disabled:cursor-not-allowed'
          }
          onClick={() => applyFilterAndRefresh(filter, false)}
        >
          Main thread
        </button>
        {(hasAux || auxiliary) && (
          <button
            className={auxiliary ? 'font-medium text-blue-600' : 'text-gray-500 hover:text-gray-700'}
            onClick={() => applyFilterAndRefresh(filter, true)}
          >
            Auxiliary threads
          </button>
        )}
        <span className="text-gray-300">|</span>
        <Link to={flameGraphHref} className="text-blue-600 hover:underline text-xs">
          View as flame graph
        </Link>
      </div>

      {/* Filter */}
      <div className="flex items-center gap-2">
        <input
          className="rounded border px-2 py-1 text-sm w-64"
          placeholder="Filter (e.g., com.example -sun.*)"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') applyFilterAndRefresh(filter, auxiliary)
          }}
        />
        <button
          className="rounded bg-blue-600 px-3 py-1 text-sm text-white hover:bg-blue-700"
          onClick={() => applyFilterAndRefresh(filter, auxiliary)}
        >
          Refresh
        </button>
        {(filter || appliedFilter) && (
          <button
            className="text-xs text-gray-500 hover:text-gray-700"
            onClick={() => {
              setFilter('')
              applyFilterAndRefresh('', auxiliary)
            }}
          >
            Clear
          </button>
        )}
      </div>

      {parsingError && (
        <div className="text-sm text-red-600">{parsingError}</div>
      )}

      {overwritten && (
        <div className="rounded-lg border bg-white p-4 text-sm text-orange-700">
          The profile data has expired.
        </div>
      )}

      {/* Profile tree */}
      {profile ? (
        <div className="rounded-lg border bg-white p-4">
          <div className="flex items-center gap-3 mb-3 text-xs">
            <span className="text-gray-500">
              {unfilteredSampleCount.toLocaleString()} samples
            </span>
            <button className="text-blue-600 hover:underline" onClick={expandAll}>
              Expand all
            </button>
            <button
              className="text-blue-600 hover:underline"
              onClick={() => {
                setExpandedPaths(new Set(['']))
                setShowAll(false)
              }}
            >
              Collapse all
            </button>
          </div>
          <div className="font-mono text-xs space-y-0">
            <ProfileTree
              node={profile}
              path=""
              expandedPaths={expandedPaths}
              toggleExpand={toggleExpand}
              totalSamples={unfilteredSampleCount}
              showAll={showAll}
              includes={filterIncludes}
            />
          </div>
        </div>
      ) : !loading ? (
        <div className="rounded-lg border bg-white p-4 text-sm text-gray-500">
          No profile data for selected time range.
        </div>
      ) : null}
    </div>
  )
}

function highlightFilterTokens(text: string, includes: string[]): ReactNode {
  if (!includes.length) return text
  const lower = text.toLowerCase()
  const parts: ReactNode[] = []
  let pos = 0
  while (pos < text.length) {
    let bestIdx = -1
    let bestLen = 0
    for (const token of includes) {
      const idx = lower.indexOf(token, pos)
      if (idx !== -1 && (bestIdx === -1 || idx < bestIdx || (idx === bestIdx && token.length > bestLen))) {
        bestIdx = idx
        bestLen = token.length
      }
    }
    if (bestIdx === -1 || bestLen === 0) break
    if (bestIdx > pos) {
      parts.push(text.slice(pos, bestIdx))
    }
    parts.push(<strong key={bestIdx}>{text.slice(bestIdx, bestIdx + bestLen)}</strong>)
    pos = bestIdx + bestLen
  }
  if (parts.length === 0) return text
  if (pos < text.length) {
    parts.push(text.slice(pos))
  }
  return parts
}

function nodeMatchesIncludes(node: ProfileNode, includes: string[]): boolean {
  if (!includes.length) return false
  const elem = (node.stackTraceElement || '').toLowerCase()
  const state = (node.leafThreadState || '').toLowerCase()
  return includes.some((t) => elem.includes(t) || state.includes(t))
}

function ProfileTree({
  node,
  path,
  expandedPaths,
  toggleExpand,
  totalSamples,
  showAll,
  includes,
  depth = 0,
}: {
  node: ProfileNode
  path: string
  expandedPaths: Set<string>
  toggleExpand: (path: string) => void
  totalSamples: number
  showAll: boolean
  includes: string[]
  depth?: number
}) {
  const [expandedChain, setExpandedChain] = useState(false)

  // Contract single-child chains at depth >= 1 (matching TraceProfileNodeView / handlebars-rendering.js)
  const chain: ProfileNode[] = [node]
  let tail = node
  if (depth >= 1) {
    while (
      tail.childNodes &&
      tail.childNodes.length === 1 &&
      !tail.leafThreadState &&
      (tail.sampleCount === tail.childNodes[0].sampleCount || tail.sampleCount < totalSamples)
    ) {
      tail = tail.childNodes[0]
      chain.push(tail)
    }
  }

  const intermediateMatched =
    chain.length > 1 && chain.slice(0, -1).some((c) => nodeMatchesIncludes(c, includes))
  const showFullChain = expandedChain || intermediateMatched

  const expanded = expandedPaths.has(path)
  const hasChildren = Boolean(tail.childNodes && tail.childNodes.length > 0)
  const pct = totalSamples > 0 ? ((100 * tail.sampleCount) / totalSamples).toFixed(1) : '0'
  const indent = depth * 16

  return (
    <>
      <div
        className="flex items-start gap-1 py-0.5 hover:bg-gray-50 cursor-pointer"
        style={{ paddingLeft: indent }}
        onClick={() => hasChildren && toggleExpand(path)}
      >
        <span className="w-4 shrink-0 text-gray-400 select-none">
          {hasChildren ? (expanded ? '▼' : '▶') : ' '}
        </span>
        <span className="text-gray-500 w-14 text-right shrink-0 tabular-nums">
          {pct}%
        </span>
        <div className="ml-1 flex-1 min-w-0 break-all">
          {chain.length === 1 ? (
            <span>
              {node.stackTraceElement
                ? highlightFilterTokens(node.stackTraceElement, includes)
                : '<root>'}
            </span>
          ) : showFullChain ? (
            <div>
              {chain.map((c, idx) => (
                <div
                  key={idx}
                  className="hover:underline"
                  onClick={(e) => {
                    e.stopPropagation()
                    setExpandedChain(false)
                  }}
                >
                  {highlightFilterTokens(c.stackTraceElement, includes)}
                </div>
              ))}
            </div>
          ) : (
            <span>
              <button
                type="button"
                className="text-blue-600 font-semibold hover:underline mr-1"
                onClick={(e) => {
                  e.stopPropagation()
                  setExpandedChain(true)
                }}
                title="Expand single-child chain"
              >
                ...
              </button>
              <span>{highlightFilterTokens(tail.stackTraceElement, includes)}</span>
            </span>
          )}
          {tail.leafThreadState && (
            <div className="text-gray-600">
              {highlightFilterTokens(tail.leafThreadState, includes)}
            </div>
          )}
        </div>
      </div>
      {expanded && hasChildren && (
        <>
          {(showAll ? tail.childNodes! : tail.childNodes!.slice(0, 20)).map((child, i) => (
            <ProfileTree
              key={i}
              node={child}
              path={path + '/' + i}
              expandedPaths={expandedPaths}
              toggleExpand={toggleExpand}
              totalSamples={totalSamples}
              showAll={showAll}
              includes={includes}
              depth={depth + 1}
            />
          ))}
          {!showAll && tail.childNodes!.length > 20 && (
            <div className="text-xs text-gray-400 pl-4" style={{ paddingLeft: indent + 16 }}>
              ... and {tail.childNodes!.length - 20} more
            </div>
          )}
        </>
      )}
      {(!hasChildren || expanded) &&
        tail.ellipsedSampleCount !== undefined &&
        tail.ellipsedSampleCount > 0 && (
          <div
            className="py-0.5 text-xs italic text-gray-500"
            style={{ paddingLeft: indent + 20 }}
          >
            ... ({tail.ellipsedSampleCount.toLocaleString()}{' '}
            {tail.ellipsedSampleCount === 1 ? 'sample' : 'samples'} in truncated branches)
          </div>
        )}
    </>
  )
}
