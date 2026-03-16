/*
 * LayoutProvider — reads window.layout, polls /backend/check-layout every 60s,
 * handles version changes by re-fetching the full layout.
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
import type { Layout } from '../types/layout'
import { apiGet, setLayoutVersion, setOnLayoutRefresh } from '../lib/api'
import { HttpError } from '../components/shared/HttpError'

interface LayoutContextValue {
  layout: Layout
  refreshLayout: () => Promise<void>
  navbarErrorMessage: string | null
  setNavbarErrorMessage: (msg: string | null) => void
}

const LayoutContext = createContext<LayoutContextValue | null>(null)

export function useLayout(): LayoutContextValue {
  const ctx = useContext(LayoutContext)
  if (!ctx) throw new Error('useLayout must be used within LayoutProvider')
  return ctx
}

function isTimeZoneSupported(timeZoneId: string): boolean {
  try {
    new Intl.DateTimeFormat(undefined, { timeZone: timeZoneId })
    return true
  } catch {
    return false
  }
}

export function filterSupportedTimeZones(layout: Layout): Layout {
  if (!Array.isArray(layout.timeZoneIds)) return layout
  const filtered = layout.timeZoneIds.filter(isTimeZoneSupported)
  if (filtered.length === layout.timeZoneIds.length) return layout
  return { ...layout, timeZoneIds: filtered }
}

export function LayoutProvider({ children }: { children: ReactNode }) {
  const [layout, setLayout] = useState<Layout | null>(() => {
    const w = window as unknown as Record<string, unknown>
    return w.layout ? filterSupportedTimeZones(w.layout as Layout) : null
  })
  const [layoutLoadError, setLayoutLoadError] = useState<unknown>(null)
  const [navbarErrorMessage, setNavbarErrorMessage] = useState<string | null>(null)

  const layoutRef = useRef(layout)
  layoutRef.current = layout
  const glowrootVersionRef = useRef<string | null>(layout?.glowrootVersion ?? null)
  const reloadTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const startUpgradeCountdown = useCallback((remaining: number) => {
    if (reloadTimerRef.current) {
      clearTimeout(reloadTimerRef.current)
      reloadTimerRef.current = null
    }
    if (remaining <= 0) {
      window.location.reload()
      return
    }
    setNavbarErrorMessage(
      `New version of Glowroot UI has been deployed, refreshing browser in ${remaining} second${remaining === 1 ? '' : 's'}...`
    )
    reloadTimerRef.current = setTimeout(() => {
      startUpgradeCountdown(remaining - 1)
    }, 1000)
  }, [])

  useEffect(() => {
    return () => {
      if (reloadTimerRef.current) {
        clearTimeout(reloadTimerRef.current)
      }
    }
  }, [])

  const refreshLayout = useCallback(async () => {
    try {
      const data = await apiGet<Layout>('/backend/layout')
      if (
        glowrootVersionRef.current &&
        data.glowrootVersion &&
        glowrootVersionRef.current !== data.glowrootVersion
      ) {
        startUpgradeCountdown(10)
      }
      glowrootVersionRef.current = data.glowrootVersion
      const filtered = filterSupportedTimeZones(data)
      setLayout(filtered)
      setLayoutVersion(filtered.version)
      setLayoutLoadError(null)
      layoutRef.current = filtered
    } catch (e) {
      if (!layoutRef.current) {
        setLayoutLoadError(e)
      } else {
        const msg = e instanceof Error && e.message ? `: ${e.message}` : ''
        setNavbarErrorMessage('An error occurred getting layout' + msg)
      }
    }
  }, [startUpgradeCountdown])

  // Wire the api module's onLayoutRefresh callback to us
  useEffect(() => {
    setOnLayoutRefresh(() => {
      refreshLayout()
    })
  }, [refreshLayout])

  // Set initial layout version
  useEffect(() => {
    if (layout) {
      setLayoutVersion(layout.version)
    }
  }, [layout])

  // Fetch layout if not available from window (dev mode)
  useEffect(() => {
    if (!layout && !layoutLoadError) {
      refreshLayout()
    }
  }, [layout, layoutLoadError, refreshLayout])

  // Poll /backend/check-layout every 60s (triggers version check & 401 handling via api.ts interceptor)
  useEffect(() => {
    const timer = setInterval(async () => {
      if (document.hidden) return
      try {
        const params: Record<string, string | undefined> = {}
        if (layoutRef.current?.central) {
          const sp = new URLSearchParams(window.location.search)
          const id = sp.get('agent-rollup-id') || sp.get('agent-id')
          if (id) {
            params['agent-rollup-id'] = id
          }
        }
        await apiGet('/backend/check-layout', params)
      } catch {
        // ignore network errors on background check-layout poll
      }
    }, 60000)
    return () => clearInterval(timer)
  }, [])

  if (layoutLoadError) {
    return (
      <div className="mx-auto max-w-2xl p-6">
        <HttpError error={layoutLoadError} />
      </div>
    )
  }

  if (!layout) {
    return (
      <div className="flex h-screen items-center justify-center">
        <div className="text-muted-foreground">Loading...</div>
      </div>
    )
  }

  return (
    <LayoutContext.Provider
      value={{ layout, refreshLayout, navbarErrorMessage, setNavbarErrorMessage }}
    >
      {children}
    </LayoutContext.Provider>
  )
}
