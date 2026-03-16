import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate, useLocation, useSearchParams } from 'react-router-dom'
import { useLayout } from '../../contexts/LayoutContext'
import { useAgent } from '../../contexts/AgentContext'
import { apiGet } from '../../lib/api'
import { applyLast, RANGE_SELECTIONS } from '../../lib/chart-utils'
import { cn } from '../../lib/utils'

interface AgentRollupItem {
  id: string
  display: string
  lastDisplayPart?: string
  depth?: number
  disabled?: boolean
}

interface ComboboxOption {
  id: string
  label: string
  depth?: number
  disabled?: boolean
  selected: boolean
}

interface RollupComboboxProps {
  id: string
  ariaLabel: string
  listAriaLabel: string
  searchAriaLabel: string
  selectedLabel: string
  options: ComboboxOption[]
  topOption?: {
    id: string
    label: string
    selected: boolean
  }
  loading: boolean
  emptyMessage: string
  onOpen: () => void
  onSelect: (id: string) => void
}

function RollupCombobox({
  id,
  ariaLabel,
  listAriaLabel,
  searchAriaLabel,
  selectedLabel,
  options,
  topOption,
  loading,
  emptyMessage,
  onOpen,
  onSelect,
}: RollupComboboxProps) {
  const [open, setOpen] = useState(false)
  const [search, setSearch] = useState('')
  const containerRef = useRef<HTMLDivElement>(null)
  const searchInputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (!open) return
    function handleOutsideMouseDown(e: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false)
      }
    }
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', handleOutsideMouseDown)
    document.addEventListener('keydown', handleKeyDown)
    return () => {
      document.removeEventListener('mousedown', handleOutsideMouseDown)
      document.removeEventListener('keydown', handleKeyDown)
    }
  }, [open])

  useEffect(() => {
    if (open) {
      searchInputRef.current?.focus()
    }
  }, [open])

  function handleToggle() {
    if (open) {
      setOpen(false)
      return
    }
    setSearch('')
    setOpen(true)
    onOpen()
  }

  function handlePick(optionId: string) {
    setOpen(false)
    onSelect(optionId)
  }

  const query = search.trim().toLowerCase()
  const showTopOption =
    !!topOption && (!query || topOption.label.toLowerCase().includes(query))
  const filteredOptions = query
    ? options.filter((opt) => opt.label.toLowerCase().includes(query))
    : options
  const hasContentBelowDivider = options.length === 0 || filteredOptions.length > 0

  return (
    <div ref={containerRef} className="relative">
      <button
        type="button"
        id={id}
        aria-label={ariaLabel}
        aria-haspopup="listbox"
        aria-expanded={open}
        onClick={handleToggle}
        className={cn(
          'inline-flex items-center gap-3 rounded-lg px-4 pt-0.5 pb-1 text-[1.875rem] font-semibold leading-tight text-[#505050] transition-colors focus:outline-none focus-visible:ring-3 focus-visible:ring-[#417998]/25',
          open ? 'bg-[#d9d4c7]' : 'bg-[#edeae4] hover:bg-[#e2ddd2]'
        )}
      >
        <span className="max-w-[320px] truncate">{selectedLabel}</span>
        <span className="inline-block h-0 w-0 shrink-0 border-x-[6px] border-x-transparent border-t-[6px] border-t-[#505050]" />
      </button>

      {open && (
        <div
          role="listbox"
          aria-label={listAriaLabel}
          className="absolute left-0 top-full z-50 mt-1 min-w-[260px] max-w-[420px] rounded-lg border border-black/15 bg-white py-2 text-gray-900 shadow-md"
        >
          <div className="px-3 py-1">
            <input
              ref={searchInputRef}
              type="text"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              aria-label={searchAriaLabel}
              className="w-full rounded border border-[#ced4da] px-3 py-1.5 text-base text-[#495057] focus:border-[#417998] focus:outline-none focus:ring-3 focus:ring-[#417998]/25"
            />
          </div>

          <div className="max-h-72 overflow-y-auto py-1">
            {showTopOption && topOption && (
              <>
                <button
                  type="button"
                  role="option"
                  aria-selected={topOption.selected}
                  onClick={() => handlePick(topOption.id)}
                  className={cn(
                    'flex w-full items-center px-5 py-1 text-left text-[1.5rem] leading-snug text-[#212529] hover:bg-[#edeae4]',
                    topOption.selected && 'bg-[#edeae4] font-semibold'
                  )}
                >
                  {topOption.label}
                </button>
                {hasContentBelowDivider && (
                  <div className="my-1 border-t border-gray-200" />
                )}
              </>
            )}

            {options.length === 0 && loading ? (
              <div className="px-5 py-1.5 text-base italic text-[#6c757d]">Loading...</div>
            ) : options.length === 0 && !loading ? (
              <div className="px-5 py-1.5 text-base italic text-[#6c757d]">{emptyMessage}</div>
            ) : filteredOptions.length === 0 ? (
              !showTopOption && (
                <div className="px-5 py-1.5 text-base italic text-[#6c757d]">
                  No matching agents
                </div>
              )
            ) : (
              filteredOptions.map((opt) => (
                <button
                  key={opt.id}
                  type="button"
                  role="option"
                  aria-selected={opt.selected}
                  disabled={opt.disabled}
                  onClick={() => handlePick(opt.id)}
                  style={
                    opt.depth !== undefined
                      ? { paddingLeft: `${20 + (opt.depth + 1) * 12}px` }
                      : undefined
                  }
                  className={cn(
                    'flex w-full items-center px-5 py-1 text-left text-[1.5rem] leading-snug',
                    opt.disabled
                      ? 'cursor-not-allowed text-gray-400'
                      : 'text-[#212529] hover:bg-[#edeae4]',
                    opt.selected && 'bg-[#edeae4] font-semibold'
                  )}
                >
                  <span className="truncate">{opt.label}</span>
                </button>
              ))
            )}
          </div>
        </div>
      )}
    </div>
  )
}

const LEAF_AGENT_ONLY_PATHS = new Set([
  '/modern/jvm/environment',
  '/modern/jvm/thread-dump',
  '/modern/jvm/jstack',
  '/modern/jvm/heap-dump',
  '/modern/jvm/heap-histogram',
  '/modern/jvm/mbean-tree',
  '/modern/jvm/force-gc',
  '/modern/jvm/system-properties',
  '/modern/config/transaction',
  '/modern/config/gauge-list',
  '/modern/config/gauge',
  '/modern/config/plugin-list',
  '/modern/config/plugin',
  '/modern/config/instrumentation-list',
  '/modern/config/instrumentation',
  '/modern/config/advanced',
])

const SUB_ITEM_TO_LIST_PATH: Record<string, string> = {
  '/modern/config/gauge': '/modern/config/gauge-list',
  '/modern/config/alert': '/modern/config/alert-list',
  '/modern/config/synthetic-monitor': '/modern/config/synthetic-monitor-list',
  '/modern/config/plugin': '/modern/config/plugin-list',
  '/modern/config/instrumentation': '/modern/config/instrumentation-list',
}

function isChartRangePath(pathname: string): boolean {
  return (
    pathname.startsWith('/modern/transaction/') ||
    pathname.startsWith('/modern/error/') ||
    pathname === '/modern/jvm/gauges' ||
    pathname === '/modern/synthetic-monitors'
  )
}

export function AgentSelector() {
  const { layout } = useLayout()
  const { agentRollup } = useAgent()
  const navigate = useNavigate()
  const location = useLocation()
  const [searchParams] = useSearchParams()
  const [agents, setAgents] = useState<AgentRollupItem[]>([])
  const [childAgents, setChildAgents] = useState<AgentRollupItem[]>([])
  const [loading, setLoading] = useState(false)
  const [childLoading, setChildLoading] = useState(false)
  const topLevelFetchIdRef = useRef(0)
  const childFetchIdRef = useRef(0)

  const currentAgentId = searchParams.get('agent-id')
  const currentAgentRollupId = searchParams.get('agent-rollup-id')
  const selectedId = currentAgentRollupId || currentAgentId || ''
  const showChildDropdown =
    !!(agentRollup && agentRollup.id.includes('::')) || selectedId.includes('::')
  const topLevelId =
    agentRollup?.topLevelId ||
    (selectedId.includes('::') ? selectedId.slice(0, selectedId.indexOf('::') + 2) : selectedId)

  const isChartRange = isChartRangePath(location.pathname)
  const fromParam = searchParams.get('from')
  const toParam = searchParams.get('to')
  const lastParam = searchParams.get('last')

  const getQueryWindow = useCallback((): { from: number; to: number } => {
    if (isChartRange) {
      if (fromParam !== null && toParam !== null) {
        const fromNum = Number(fromParam)
        const toNum = Number(toParam)
        if (Number.isFinite(fromNum) && Number.isFinite(toNum) && fromNum < toNum) {
          return { from: fromNum, to: toNum }
        }
      }
      const lastNum = lastParam !== null ? Number(lastParam) : NaN
      const effectiveLast =
        Number.isFinite(lastNum) && lastNum > 0 ? lastNum : RANGE_SELECTIONS[3]
      const applied = applyLast(
        effectiveLast,
        layout.rollupConfigs,
        layout.rollupExpirationMillis,
        location.pathname === '/modern/jvm/gauges',
      )
      return { from: applied.chartFrom, to: applied.chartTo }
    }
    const now = Date.now()
    return { from: now - 7 * 24 * 60 * 60 * 1000, to: now }
  }, [
    isChartRange,
    fromParam,
    toParam,
    lastParam,
    layout.rollupConfigs,
    layout.rollupExpirationMillis,
    location.pathname,
  ])

  const fetchAgents = useCallback(async () => {
    if (!layout.central) return
    const fetchId = ++topLevelFetchIdRef.current
    setLoading(true)
    try {
      const { from, to } = getQueryWindow()
      const resp = await apiGet<AgentRollupItem[]>('/backend/top-level-agent-rollups', {
        from: String(from),
        to: String(to),
      })
      if (fetchId === topLevelFetchIdRef.current) {
        setAgents(resp)
      }
    } catch {
      // ignore
    } finally {
      if (fetchId === topLevelFetchIdRef.current) {
        setLoading(false)
      }
    }
  }, [layout.central, getQueryWindow])

  const fetchChildAgents = useCallback(async () => {
    if (
      !layout.central ||
      !showChildDropdown ||
      !topLevelId ||
      !topLevelId.endsWith('::')
    ) {
      return
    }
    const fetchId = ++childFetchIdRef.current
    setChildLoading(true)
    try {
      const { from, to } = getQueryWindow()
      const resp = await apiGet<AgentRollupItem[]>('/backend/child-agent-rollups', {
        'top-level-id': topLevelId,
        from: String(from),
        to: String(to),
      })
      if (fetchId === childFetchIdRef.current) {
        setChildAgents(resp)
      }
    } catch {
      if (fetchId === childFetchIdRef.current) {
        setChildAgents([])
      }
    } finally {
      if (fetchId === childFetchIdRef.current) {
        setChildLoading(false)
      }
    }
  }, [layout.central, showChildDropdown, topLevelId, getQueryWindow])

  useEffect(() => {
    fetchAgents()
  }, [fetchAgents])

  useEffect(() => {
    if (!layout.central || !showChildDropdown || !topLevelId || !topLevelId.endsWith('::')) {
      setChildAgents([])
      return
    }
    fetchChildAgents()
  }, [layout.central, showChildDropdown, topLevelId, fetchChildAgents])

  if (!layout.central) return null

  function handleChange(newId: string) {
    if (!newId) return
    const isRollup = newId.endsWith('::')
    const paramName = isRollup ? 'agent-rollup-id' : 'agent-id'

    let targetPath = location.pathname
    let strippedSubItemParams = false
    if (isRollup && LEAF_AGENT_ONLY_PATHS.has(location.pathname)) {
      targetPath = location.pathname.startsWith('/modern/jvm/')
        ? '/modern/jvm/gauges'
        : '/modern/config/general'
      strippedSubItemParams = true
    } else if (SUB_ITEM_TO_LIST_PATH[location.pathname]) {
      targetPath = SUB_ITEM_TO_LIST_PATH[location.pathname]
      strippedSubItemParams = true
    }

    const newParams = new URLSearchParams()
    newParams.set(paramName, newId)
    for (const [key, value] of searchParams.entries()) {
      if (key === 'agent-id' || key === 'agent-rollup-id') continue
      if (key === 'transaction-name' || key === 'synthetic-monitor-id') continue
      if (
        strippedSubItemParams &&
        (key === 'v' || key === 'id' || key === 'plugin-id' || key === 'new')
      ) {
        continue
      }
      newParams.set(key, value)
    }
    navigate(targetPath + '?' + newParams.toString())
  }

  const emptyMessage = isChartRange
    ? 'No active agents in this time period'
    : 'No active agents in the past 7 days'

  const currentTopValue = showChildDropdown ? topLevelId : selectedId
  const selectedTopAgent = agents.find((agent) => agent.id === currentTopValue)
  const topSelectedLabel = !currentTopValue
    ? '<select>'
    : selectedTopAgent?.display ||
      agentRollup?.topLevelDisplay ||
      (currentTopValue.endsWith('::') ? currentTopValue.slice(0, -2) : currentTopValue)

  const selectedChildAgent = childAgents.find((child) => child.id === selectedId)
  const childSelectedLabel =
    selectedId === topLevelId
      ? 'Rollup'
      : selectedChildAgent?.lastDisplayPart ||
        selectedChildAgent?.display ||
        agentRollup?.childDisplay ||
        agentRollup?.lastDisplayPart ||
        selectedId

  return (
    <div className="flex shrink-0 items-center gap-2">
      <RollupCombobox
        id="topLevelAgentRollupDropdown"
        ariaLabel="Top-level agent rollup"
        listAriaLabel="Top-level agent rollup options"
        searchAriaLabel="Search agents"
        selectedLabel={topSelectedLabel}
        options={agents.map((agent) => ({
          id: agent.id,
          label: agent.display,
          disabled: agent.disabled,
          selected: agent.id === currentTopValue,
        }))}
        loading={loading}
        emptyMessage={emptyMessage}
        onOpen={fetchAgents}
        onSelect={handleChange}
      />

      {showChildDropdown && (
        <RollupCombobox
          id="childAgentRollupDropdown"
          ariaLabel="Child agent rollup"
          listAriaLabel="Child agent rollup options"
          searchAriaLabel="Search child agents"
          selectedLabel={childSelectedLabel}
          topOption={{
            id: topLevelId,
            label: 'Rollup',
            selected: selectedId === topLevelId,
          }}
          options={childAgents.map((child) => ({
            id: child.id,
            label: child.lastDisplayPart || child.display,
            depth: child.depth ?? 0,
            disabled: child.disabled,
            selected: child.id === selectedId,
          }))}
          loading={childLoading}
          emptyMessage={emptyMessage}
          onOpen={fetchChildAgents}
          onSelect={handleChange}
        />
      )}
    </div>
  )
}
