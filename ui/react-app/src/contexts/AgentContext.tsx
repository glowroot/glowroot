/*
 * AgentProvider — manages agent/agentRollup selection state.
 *
 * In embedded mode, the agent is always the embedded agent from the layout.
 * In central mode, the agent is determined by query parameters (agent-id or agent-rollup-id).
 */

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import { useSearchParams } from 'react-router-dom'
import type { AgentRollupLayout } from '../types/layout'
import { useLayout } from './LayoutContext'
import {
  apiGet,
  setAgentRollupLayoutVersion,
  setOnAgentRollupLayoutRefresh,
} from '../lib/api'

interface AgentContextValue {
  /** Currently selected agent rollup (null in central mode before selection) */
  agentRollup: AgentRollupLayout | null
  /** The agent-id query parameter (specific agent, not rollup) */
  agentId: string | null
  /** The agent-rollup-id query parameter (may be a rollup) */
  agentRollupId: string | null
  /** Whether the current selection is a rollup (ends with "::") */
  isRollup: boolean
  /** Build query string for agent-aware links */
  agentQueryString: () => string
  /** Refresh agent rollup data from backend */
  refreshAgentRollup: () => Promise<void>
}

const AgentContext = createContext<AgentContextValue | null>(null)

export function useAgent(): AgentContextValue {
  const ctx = useContext(AgentContext)
  if (!ctx) throw new Error('useAgent must be used within AgentProvider')
  return ctx
}

export function AgentProvider({ children }: { children: ReactNode }) {
  const { layout, setNavbarErrorMessage } = useLayout()
  const [searchParams] = useSearchParams()
  const [agentRollup, setAgentRollup] = useState<AgentRollupLayout | null>(() =>
    !layout.central ? layout.embeddedAgentRollup : null
  )

  const agentIdParam = searchParams.get('agent-id')
  const agentRollupIdParam = searchParams.get('agent-rollup-id')

  // In embedded mode, the agent is always from the layout (empty string "")
  const agentId = layout.central ? agentIdParam : layout.embeddedAgentRollup?.id ?? null
  const agentRollupId = layout.central
    ? agentRollupIdParam ?? agentIdParam
    : layout.embeddedAgentRollup?.id ?? null
  const isRollup = agentRollupId ? agentRollupId.endsWith('::') : false

  const activeAgentRollupIdRef = useRef<string | null>(agentRollupId)
  activeAgentRollupIdRef.current = agentRollupId

  const fetchAgentRollupLayout = useCallback(
    async (id: string) => {
      try {
        const data = await apiGet<AgentRollupLayout>(
          '/backend/agent-rollup-layout',
          { 'agent-rollup-id': id }
        )
        if (activeAgentRollupIdRef.current === id) {
          setAgentRollup(data)
        }
      } catch (e) {
        const msg = e instanceof Error && e.message ? `: ${e.message}` : ''
        setNavbarErrorMessage('An error occurred getting agent rollup layout' + msg)
      }
    },
    [setNavbarErrorMessage]
  )

  const refreshAgentRollup = useCallback(async () => {
    if (!layout.central) {
      setAgentRollup(layout.embeddedAgentRollup)
      return
    }
    const id = agentRollupIdParam ?? agentIdParam
    if (!id) {
      setAgentRollup(null)
      return
    }
    try {
      const data = await apiGet<AgentRollupLayout>(
        '/backend/agent-rollup-layout',
        { 'agent-rollup-id': id }
      )
      setAgentRollup(data)
    } catch {
      setAgentRollup(null)
    }
  }, [layout.central, layout.embeddedAgentRollup, agentIdParam, agentRollupIdParam])

  useEffect(() => {
    refreshAgentRollup()
  }, [refreshAgentRollup])

  useEffect(() => {
    setAgentRollupLayoutVersion(agentRollup?.version ?? null)
  }, [agentRollup?.version])

  useEffect(() => {
    setOnAgentRollupLayoutRefresh(() => {
      const id = activeAgentRollupIdRef.current
      if (id !== null) {
        fetchAgentRollupLayout(id)
      }
    })
  }, [fetchAgentRollupLayout])

  const buildAgentQueryString = useCallback((): string => {
    if (!layout.central) return ''
    if (!isRollup && agentId !== null && agentId !== '') {
      return '?agent-id=' + encodeURIComponent(agentId)
    }
    if (agentRollupId !== null && agentRollupId !== '') {
      return '?agent-rollup-id=' + encodeURIComponent(agentRollupId)
    }
    return ''
  }, [layout.central, isRollup, agentRollupId, agentId])

  return (
    <AgentContext.Provider
      value={{
        agentRollup,
        agentId,
        agentRollupId,
        isRollup,
        agentQueryString: buildAgentQueryString,
        refreshAgentRollup,
      }}
    >
      {children}
    </AgentContext.Provider>
  )
}
