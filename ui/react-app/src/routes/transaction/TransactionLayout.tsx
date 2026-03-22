/*
 * TransactionLayout — shared layout for transaction and error pages.
 *
 * Provides: Classic-style header (agent selector / title + transaction type + chart range),
 * sidebar with summaries, card header & tabs, and child page outlet.
 */

import { useEffect, useRef, useState, type KeyboardEvent } from 'react'
import { Link, Outlet, useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { cn } from '../../lib/utils'
import {
  useTransaction,
  TRANSACTION_SORT_ORDERS,
  ERROR_SORT_ORDERS,
} from '../../contexts/TransactionContext'
import { useAgent } from '../../contexts/AgentContext'
import { useLayout } from '../../contexts/LayoutContext'
import { AgentSelector } from '../../components/layout/AgentSelector'
import { ChartRangeSelector } from '../../components/chart/ChartRangeSelector'

function useDropdownDismiss(open: boolean, onClose: () => void) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    function handleMouseDown(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        onClose()
      }
    }
    function handleKeyDown(e: globalThis.KeyboardEvent) {
      if (e.key === 'Escape') {
        onClose()
      }
    }
    document.addEventListener('mousedown', handleMouseDown)
    document.addEventListener('keydown', handleKeyDown)
    return () => {
      document.removeEventListener('mousedown', handleMouseDown)
      document.removeEventListener('keydown', handleKeyDown)
    }
  }, [open, onClose])
  return ref
}

export function TransactionLayout() {
  const { layout } = useLayout()
  const { agentRollup, agentRollupId } = useAgent()
  const txn = useTransaction()
  const location = useLocation()
  const [searchParams, setSearchParams] = useSearchParams()
  const [typeMenuOpen, setTypeMenuOpen] = useState(false)
  const [sortMenuOpen, setSortMenuOpen] = useState(false)

  const typeMenuRef = useDropdownDismiss(typeMenuOpen, () => setTypeMenuOpen(false))
  const sortMenuRef = useDropdownDismiss(sortMenuOpen, () => setSortMenuOpen(false))

  const needsAgent = layout.central && !agentRollupId

  document.title = `${txn.headerDisplay} · Glowroot`

  const isTransaction = txn.shortName === 'transaction'
  const sortOrders = isTransaction ? TRANSACTION_SORT_ORDERS : ERROR_SORT_ORDERS

  const transactionTypes = agentRollup?.transactionTypes ?? []
  const hideTransactionTypeDropdown =
    !!agentRollup &&
    transactionTypes.length === 1 &&
    transactionTypes[0] === txn.transactionType
  const otherTransactionTypes = transactionTypes.filter((t) => t !== txn.transactionType)
  const embeddedHeaderTitle =
    layout.embeddedAgentRollup?.topLevelDisplay || txn.headerDisplay

  function sidebarQs(transactionName: string | null) {
    const params = new URLSearchParams(searchParams)
    if (transactionName) {
      params.set('transaction-name', transactionName)
    } else {
      params.delete('transaction-name')
    }
    const defaultSortOrder = isTransaction ? 'total-time' : 'error-count'
    if (txn.summarySortOrder && txn.summarySortOrder !== defaultSortOrder) {
      params.set('summary-sort-order', txn.summarySortOrder)
    } else {
      params.delete('summary-sort-order')
    }
    const qs = params.toString()
    return qs ? '?' + qs : ''
  }

  // Current tab path segment
  const basePath = `/modern/${txn.shortName}`
  const pathAfterBase = location.pathname.replace(basePath + '/', '')

  const activeScopeTitle = txn.transactionName
    ? txn.transactionName
    : txn.transactionType
      ? `All ${txn.transactionType} Transactions`
      : 'All Transactions'

  return (
    <div>
      {/* Header */}
      <div className="gt-header flex flex-wrap items-center justify-between gap-4 pt-5 pb-12">
        <div className="gt-header-lhs flex flex-wrap items-center gap-1">
          {layout.central ? (
            <AgentSelector />
          ) : (
            <h1 className="gt-header-text m-0 text-[2.25rem] font-bold leading-none text-[#785b12]">
              {embeddedHeaderTitle}
            </h1>
          )}

          <div
            aria-hidden="true"
            className="gt-header-text gt-separator mx-1.5 -mt-2 select-none text-[3.25rem] font-normal leading-none text-[#785b12]"
          >
            |
          </div>

          {hideTransactionTypeDropdown ? (
            <div className="gt-header-text text-[2.25rem] font-bold leading-none text-[#785b12]">
              {txn.transactionType}
            </div>
          ) : (
            <div ref={typeMenuRef} className="relative">
              <button
                type="button"
                id="gtTransactionType"
                aria-haspopup="true"
                aria-expanded={typeMenuOpen}
                onClick={() => setTypeMenuOpen((open) => !open)}
                className={cn(
                  'inline-flex items-center gap-3 rounded-lg px-4 pt-0.5 pb-1 text-[1.875rem] font-semibold leading-tight text-[#505050] transition-colors focus:outline-none focus-visible:ring-3 focus-visible:ring-[#417998]/25',
                  typeMenuOpen ? 'bg-[#d9d4c7]' : 'bg-[#edeae4] hover:bg-[#e2ddd2]'
                )}
              >
                <span>{txn.transactionType || '<transaction type>'}</span>
                <span className="inline-block h-0 w-0 shrink-0 border-x-[6px] border-x-transparent border-t-[6px] border-t-[#505050]" />
              </button>
              {typeMenuOpen && otherTransactionTypes.length > 0 && (
                <div className="absolute left-0 top-full z-50 mt-1 min-w-full whitespace-nowrap rounded-lg border border-black/15 bg-white py-1.5 shadow-md">
                  {otherTransactionTypes.map((t) => (
                    <button
                      key={t}
                      type="button"
                      onClick={() => {
                        setTypeMenuOpen(false)
                        setSearchParams((prev) => {
                          const next = new URLSearchParams(prev)
                          next.set('transaction-type', t)
                          next.delete('transaction-name')
                          return next
                        })
                      }}
                      className="block w-full px-5 py-1 text-left text-[1.5rem] text-[#212529] hover:bg-[#edeae4]"
                    >
                      {t}
                    </button>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>

        <div className="gt-header-rhs flex items-center pt-2 print:hidden">
          <ChartRangeSelector range={txn.range} actions={txn.rangeActions} />
        </div>
      </div>

      {/* Two-column sidebar + card */}
      <div className="flex flex-col gap-5 md:flex-row md:items-start">
        {/* Sidebar */}
        <div className="w-52 shrink-0 md:w-[220px] lg:w-[300px] 2xl:w-[400px] print:hidden">
          {/* Sort order dropdown */}
          <div ref={sortMenuRef} className="relative mb-1 rounded border border-[#d2ba7f] bg-[#edeae4]">
            <button
              type="button"
              id="gtSidebarSortBy"
              aria-haspopup="true"
              aria-expanded={sortMenuOpen}
              onClick={() => setSortMenuOpen((open) => !open)}
              className="flex w-full items-center justify-between px-5 py-2 text-left text-base text-[#212529] focus:outline-none focus-visible:ring-3 focus-visible:ring-[#417998]/25"
            >
              <span>{sortOrders[txn.summarySortOrder] || txn.summarySortOrder}</span>
              <span className="inline-block h-0 w-0 shrink-0 border-x-[4px] border-x-transparent border-t-[4px] border-t-[#212529]" />
            </button>
            {sortMenuOpen && (
              <div className="absolute -left-px -right-px top-full z-40 mt-0.5 rounded border border-[#d9d9d9] bg-white py-1 shadow-md">
                {Object.entries(sortOrders)
                  .filter(([value]) => value !== txn.summarySortOrder)
                  .map(([value, display]) => (
                    <button
                      key={value}
                      type="button"
                      onClick={() => {
                        setSortMenuOpen(false)
                        txn.setSummarySortOrder(value)
                      }}
                      className="block w-full px-5 py-1.5 text-left text-sm text-[#212529] hover:bg-[#edeae4]"
                    >
                      {display}
                    </button>
                  ))}
              </div>
            )}
          </div>

          {/* Summaries list */}
          <div className="overflow-hidden rounded border border-[#d2ba7f] bg-white">
            <div className="flex flex-col">
              {needsAgent || !txn.transactionType ? (
                <div className="h-[100px] w-full" />
              ) : txn.summaryLoading && txn.transactionSummaries.length === 0 ? (
                <div className="flex h-[100px] items-center justify-center text-sm text-gray-400">
                  Loading...
                </div>
              ) : txn.transactionSummaries.length === 0 ? (
                <div className="flex h-[100px] items-center justify-center text-base font-semibold text-[#444]">
                  No data for this time period
                </div>
              ) : (
                <>
                  {/* Overall */}
                  <Link
                    to={`${location.pathname}${sidebarQs(null)}`}
                    className={cn(
                      'flex items-center justify-between border-b border-gray-200 px-3 py-2 text-sm no-underline',
                      !txn.transactionName
                        ? 'bg-[#edeae4] font-semibold text-black'
                        : 'text-[#555] hover:bg-[#f5f5f5]'
                    )}
                  >
                    <span className="break-all">
                      All {txn.transactionType} Transactions
                    </span>
                    {txn.overallSummary && (
                      <span className="ml-2 whitespace-nowrap text-xs text-gray-500">
                        {txn.summaryValueFn(txn.overallSummary)}
                      </span>
                    )}
                  </Link>
                  <div className="h-[3px] bg-gray-100" />
                  {/* Individual transactions */}
                  {txn.transactionSummaries.map((s) => (
                    <Link
                      key={s.transactionName}
                      to={`${location.pathname}${sidebarQs(s.transactionName)}`}
                      className={cn(
                        'flex items-center justify-between border-b border-gray-200 px-3 py-2 text-sm no-underline last:border-b-0',
                        txn.transactionName === s.transactionName
                          ? 'bg-[#edeae4] font-semibold text-black'
                          : 'text-[#555] hover:bg-[#f5f5f5]'
                      )}
                    >
                      <span className="break-all">{s.transactionName}</span>
                      <span className="ml-2 whitespace-nowrap text-xs text-gray-500">
                        {txn.summaryValueFn(s)}
                      </span>
                    </Link>
                  ))}
                  {txn.moreSummariesAvailable && (
                    <button
                      type="button"
                      className="w-full px-3 py-2 text-left text-sm italic text-gray-500 hover:bg-gray-50"
                      onClick={txn.showMoreSummaries}
                    >
                      Show more
                    </button>
                  )}
                </>
              )}
            </div>
          </div>
        </div>

        {/* Main content card */}
        <div className="min-w-0 flex-1">
          <div className="rounded border border-[#d2ba7f] bg-white">
            <div className="break-all border-b border-[#d2ba7f] bg-[#edeae4] px-5 py-3 text-[#555]">
              <h2 className="m-0 text-[1.75rem] font-semibold leading-tight text-[#555]">
                {activeScopeTitle}
              </h2>
            </div>

            <div className="p-6">
              {/* Tab bar */}
              <nav className="mb-5 flex border-b border-[#dee2e6]" role="tablist">
                {isTransaction ? (
                  <TransactionTabs
                    basePath={basePath}
                    qs={txn.buildTabQueryString()}
                    active={pathAfterBase}
                    showExtraTabs={!needsAgent && !!agentRollup}
                    permissions={agentRollup?.permissions?.transaction}
                    traceCount={txn.displayedTraceCount ?? txn.traceCount}
                    transactionType={txn.transactionType}
                    onRefresh={txn.rangeActions.refresh}
                  />
                ) : (
                  <ErrorTabs
                    basePath={basePath}
                    qs={txn.buildTabQueryString()}
                    active={pathAfterBase}
                    showExtraTabs={!needsAgent && !!agentRollup}
                    permissions={agentRollup?.permissions?.error}
                    traceCount={txn.displayedTraceCount ?? txn.traceCount}
                    onRefresh={txn.rangeActions.refresh}
                  />
                )}
              </nav>

              {/* Page content */}
              {!needsAgent && !!txn.transactionType ? (
                <Outlet />
              ) : (
                <div className="min-h-[40px]" />
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

function TransactionTabs({
  basePath,
  qs,
  active,
  showExtraTabs,
  permissions,
  traceCount,
  transactionType,
  onRefresh,
}: {
  basePath: string
  qs: string
  active: string
  showExtraTabs: boolean
  permissions?: { overview: boolean; traces: boolean; queries: boolean; serviceCalls: boolean; threadProfile: boolean }
  traceCount?: number
  transactionType: string
  onRefresh: () => void
}) {
  const navigate = useNavigate()
  const tabs = [
    {
      id: 'time',
      domId: 'tab_time',
      label: 'Response time',
      paths: ['average', 'percentiles', 'throughput'],
      href: `${basePath}/average${qs}`,
    },
    showExtraTabs && permissions?.traces !== false && {
      id: 'traces',
      domId: 'tab_traces',
      label: `${transactionType === 'Startup' ? 'Traces' : 'Slow traces'} (${traceCount !== undefined ? traceCount.toLocaleString() : '...'})`,
      paths: ['traces'],
      href: `${basePath}/traces${qs}`,
    },
    showExtraTabs && permissions?.queries !== false && {
      id: 'queries',
      domId: 'tab_queries',
      label: 'Queries',
      paths: ['queries'],
      href: `${basePath}/queries${qs}`,
    },
    showExtraTabs && permissions?.serviceCalls !== false && {
      id: 'service-calls',
      domId: 'tab_service_calls',
      label: 'Service calls',
      paths: ['service-calls'],
      href: `${basePath}/service-calls${qs}`,
    },
    showExtraTabs && permissions?.threadProfile !== false && {
      id: 'thread-profile',
      domId: 'tab_thread_profile',
      label: 'Thread profile',
      paths: ['thread-profile'],
      href: `${basePath}/thread-profile${qs}`,
    },
  ].filter(Boolean) as Array<{ id: string; domId: string; label: string; paths: string[]; href: string }>

  function handleTabKeyDown(e: KeyboardEvent<HTMLAnchorElement>, index: number) {
    if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return
    e.preventDefault()
    const targetIdx = e.key === 'ArrowLeft' ? index - 1 : index + 1
    if (targetIdx < 0 || targetIdx >= tabs.length) return
    const target = tabs[targetIdx]
    document.getElementById(target.domId)?.focus()
    navigate(target.href)
  }

  return (
    <>
      {tabs.map((tab, idx) => {
        const isActive = tab.paths.includes(active)
        return (
          <Link
            key={tab.id}
            id={tab.domId}
            role="tab"
            aria-selected={isActive}
            to={tab.href}
            onClick={(e) => {
              if (isActive) {
                e.preventDefault()
                onRefresh()
              }
            }}
            onKeyDown={(e) => handleTabKeyDown(e, idx)}
            className={cn(
              '-mb-px rounded-t border px-4 py-2 text-base no-underline',
              isActive
                ? 'border-[#dee2e6] border-b-white bg-white text-[#495057]'
                : 'border-transparent font-semibold text-[#417998] hover:border-[#e9ecef_#e9ecef_#dee2e6]'
            )}
          >
            {tab.label}
          </Link>
        )
      })}
    </>
  )
}

function ErrorTabs({
  basePath,
  qs,
  active,
  showExtraTabs,
  permissions,
  traceCount,
  onRefresh,
}: {
  basePath: string
  qs: string
  active: string
  showExtraTabs: boolean
  permissions?: { overview: boolean; traces: boolean }
  traceCount?: number
  onRefresh: () => void
}) {
  const navigate = useNavigate()
  const tabs = [
    {
      id: 'messages',
      domId: 'tab_messages',
      label: 'Error messages',
      paths: ['messages'],
      href: `${basePath}/messages${qs}`,
    },
    showExtraTabs && permissions?.traces !== false && {
      id: 'traces',
      domId: 'tab_traces',
      label: `Error traces (${traceCount !== undefined ? traceCount.toLocaleString() : '...'})`,
      paths: ['traces'],
      href: `${basePath}/traces${qs}`,
    },
  ].filter(Boolean) as Array<{ id: string; domId: string; label: string; paths: string[]; href: string }>

  function handleTabKeyDown(e: KeyboardEvent<HTMLAnchorElement>, index: number) {
    if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return
    e.preventDefault()
    const targetIdx = e.key === 'ArrowLeft' ? index - 1 : index + 1
    if (targetIdx < 0 || targetIdx >= tabs.length) return
    const target = tabs[targetIdx]
    document.getElementById(target.domId)?.focus()
    navigate(target.href)
  }

  return (
    <>
      {tabs.map((tab, idx) => {
        const isActive = tab.paths.includes(active)
        return (
          <Link
            key={tab.id}
            id={tab.domId}
            role="tab"
            aria-selected={isActive}
            to={tab.href}
            onClick={(e) => {
              if (isActive) {
                e.preventDefault()
                onRefresh()
              }
            }}
            onKeyDown={(e) => handleTabKeyDown(e, idx)}
            className={cn(
              '-mb-px rounded-t border px-4 py-2 text-base no-underline',
              isActive
                ? 'border-[#dee2e6] border-b-white bg-white text-[#495057]'
                : 'border-transparent font-semibold text-[#417998] hover:border-[#e9ecef_#e9ecef_#dee2e6]'
            )}
          >
            {tab.label}
          </Link>
        )
      })}
    </>
  )
}
