import { Link, useLocation, useSearchParams } from 'react-router-dom'
import dayjs from 'dayjs'
import {
  Settings,
  LogOut,
  Menu,
  X,
} from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useLayout } from '../../contexts/LayoutContext'
import { useAgent } from '../../contexts/AgentContext'
import { useAuth } from '../../contexts/AuthContext'
import { toClassicUrl } from '../../lib/api'
import { encodeObject } from '../../lib/query-strings'
import { cn } from '../../lib/utils'

function parseYyyyMmDd(value: string, endOfDay = false): number | undefined {
  const m = /^(\d{4})(\d{2})(\d{2})$/.exec(value)
  if (!m) return undefined
  const year = Number(m[1])
  const month = Number(m[2]) - 1
  const day = Number(m[3]) + (endOfDay ? 1 : 0)
  return new Date(year, month, day).getTime()
}

export function Navbar() {
  const { layout, navbarErrorMessage } = useLayout()
  const { agentId, agentRollupId, isRollup, agentRollup, agentQueryString } = useAgent()
  const { loggedIn, loginEnabled, signOut, signOutMessage } = useAuth()
  const location = useLocation()
  const [searchParams] = useSearchParams()
  const [mobileOpen, setMobileOpen] = useState(false)
  const [gearsOpen, setGearsOpen] = useState(false)
  const gearsRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    function handleOutsideClick(e: MouseEvent) {
      if (gearsRef.current && !gearsRef.current.contains(e.target as Node)) {
        setGearsOpen(false)
      }
    }
    document.addEventListener('mousedown', handleOutsideClick)
    return () => document.removeEventListener('mousedown', handleOutsideClick)
  }, [])

  const hideNavbar = location.pathname === '/modern/login'
  const currentSection = location.pathname.split('/')[2] // after /modern/

  function queryString(
    preserveAgentRollup: boolean,
    preserveTransactionType: boolean,
    addDefaultGaugeNames = false
  ): string {
    const params: Record<string, unknown> = {}
    if (layout.central) {
      if (preserveAgentRollup) {
        const rollupParam = searchParams.get('agent-rollup-id')
        const idParam = searchParams.get('agent-id')
        if (rollupParam) {
          params['agent-rollup-id'] = rollupParam
        } else if (idParam) {
          params['agent-id'] = idParam
        } else if (isRollup && agentRollupId) {
          params['agent-rollup-id'] = agentRollupId
        } else if (agentId) {
          params['agent-id'] = agentId
        }
      } else {
        const idParam = searchParams.get('agent-id')
        if (idParam) {
          params['agent-id'] = idParam
        } else if (!isRollup && agentId) {
          params['agent-id'] = agentId
        }
      }
    }
    if (preserveTransactionType) {
      const txnType =
        searchParams.get('transaction-type') ||
        agentRollup?.defaultTransactionType
      if (txnType) {
        params['transaction-type'] = txnType
      }
    }
    if (addDefaultGaugeNames && currentSection !== 'jvm' && agentRollup?.defaultGaugeNames?.length) {
      params['gauge-name'] = agentRollup.defaultGaugeNames
    }
    const fromDate = searchParams.get('from-date')
    const toDate = searchParams.get('to-date')
    const last = searchParams.get('last')
    const from = searchParams.get('from')
    const to = searchParams.get('to')
    if (location.pathname === '/modern/report/ad-hoc' && fromDate && toDate) {
      const fromMs = parseYyyyMmDd(fromDate, false)
      const toMs = parseYyyyMmDd(toDate, true)
      if (fromMs !== undefined && toMs !== undefined) {
        params.from = fromMs
        params.to = toMs
      }
    } else if (last) {
      params.last = last
    } else if (from && to) {
      const fromNum = Number(from)
      const toNum = Number(to)
      if (!Number.isNaN(fromNum) && !Number.isNaN(toNum)) {
        params.from = Math.floor(fromNum / 60000) * 60000
        params.to = Math.ceil(toNum / 60000) * 60000
      }
    }
    return encodeObject(params)
  }

  function reportQueryString(): string {
    const params: Record<string, unknown> = {}
    const fromDate = searchParams.get('from-date')
    const toDate = searchParams.get('to-date')
    const last = searchParams.get('last')
    const from = searchParams.get('from')
    const to = searchParams.get('to')
    if (fromDate && toDate) {
      params['from-date'] = fromDate
      params['to-date'] = toDate
    } else if (last) {
      const lastNum = Number(last)
      if (!Number.isNaN(lastNum)) {
        const now = Date.now()
        params['from-date'] = dayjs(now - lastNum).format('YYYYMMDD')
        params['to-date'] = dayjs(now).format('YYYYMMDD')
      }
    } else if (from && to) {
      const fromNum = Number(from)
      const toNum = Number(to)
      if (!Number.isNaN(fromNum) && !Number.isNaN(toNum)) {
        params['from-date'] = dayjs(fromNum).format('YYYYMMDD')
        params['to-date'] = dayjs(toNum).format('YYYYMMDD')
      }
    }
    return encodeObject(params)
  }

  function configUrl(): string {
    const base = layout.central ? '/modern/config/general' : '/modern/config/transaction'
    return base + agentQueryString()
  }

  function navItem(
    label: string,
    section: string,
    path: string,
    qs: string,
    show: boolean
  ) {
    if (!show) return null
    const href = '/modern/' + path + qs
    const active =
      currentSection === section ||
      (section === 'syntheticMonitor' && currentSection === 'synthetic-monitors')
    return (
      <Link
        key={section}
        to={href}
        onClick={() => setMobileOpen(false)}
        className={cn(
          'flex items-center px-3 py-3 text-sm font-semibold transition-colors no-underline',
          'text-white hover:[text-shadow:0_0_4px_#fff]',
          active ? 'bg-[#785b12]' : ''
        )}
      >
        {label}
      </Link>
    )
  }

  const showGears =
    layout.showNavbarConfig ||
    layout.adminView ||
    (!loggedIn && loginEnabled) ||
    (loggedIn && !layout.ldap)

  const classicHref = toClassicUrl(location.pathname, location.search)

  return (
    <>
      <nav className="gt-navbar bg-[#8e6c15] text-white">
        <div className="mx-auto max-w-[1340px] 2xl:max-w-[1540px] px-4">
          <div className="flex min-h-12 items-center justify-between">
            {/* Brand */}
            <Link
              to="/modern/"
              className="navbar-brand text-xl font-bold text-white mr-5 no-underline [text-shadow:0_0_4px_#fff]"
            >
              Glowroot
            </Link>

            {!hideNavbar && (
              <>
                {/* Desktop nav */}
                <div className="hidden md:flex flex-1 items-center gap-0.5">
                  {navItem('Transactions', 'transaction', 'transaction/average', queryString(true, true), layout.showNavbarTransaction)}
                  {navItem('Errors', 'error', 'error/messages', queryString(true, true), layout.showNavbarError)}
                  {navItem('JVM', 'jvm', 'jvm/gauges', queryString(false, false, true), layout.showNavbarJvm)}
                  {layout.central && navItem('Synthetic', 'syntheticMonitor', 'synthetic-monitors', queryString(true, false), layout.showNavbarSyntheticMonitor)}
                  {navItem('Incidents', 'incidents', 'incidents', '', layout.showNavbarIncident)}
                  {navItem('Reporting', 'report', 'report/ad-hoc', reportQueryString(), layout.showNavbarReport)}
                </div>

                {/* Right side */}
                <div className="hidden md:flex items-center gap-1">
                  {/* Classic UI link */}
                  <a
                    href={classicHref}
                    className="px-2 py-1 text-xs font-semibold text-white/80 hover:text-white hover:[text-shadow:0_0_4px_#fff] transition-colors no-underline"
                    target="_self"
                  >
                    Classic UI
                  </a>

                  {showGears && (
                    <div ref={gearsRef} className="relative">
                      <button
                        id="gtGearsMenuToggle"
                        type="button"
                        onClick={() => setGearsOpen((open) => !open)}
                        className={cn(
                          'p-3 text-white hover:[text-shadow:0_0_10px_#fff]',
                          (currentSection === 'config' || currentSection === 'admin' || currentSection === 'profile') && 'bg-[#785b12]'
                        )}
                        aria-label="Settings"
                        aria-haspopup="true"
                        aria-expanded={gearsOpen}
                      >
                        <Settings className="h-5 w-5" />
                      </button>
                      {gearsOpen && (
                        <div
                          id="gtGearsMenu"
                          className="absolute right-0 top-full z-50 w-48 bg-white text-gray-800 rounded shadow-lg border py-1"
                        >
                          {layout.showNavbarConfig && (
                            <Link
                              to={configUrl()}
                              onClick={() => setGearsOpen(false)}
                              className="block px-4 py-1.5 text-sm hover:bg-[#edeae4] no-underline text-gray-800"
                            >
                              Configuration
                            </Link>
                          )}
                          {layout.adminView && (
                            <Link
                              to="/modern/admin/general"
                              onClick={() => setGearsOpen(false)}
                              className="block px-4 py-1.5 text-sm hover:bg-[#edeae4] no-underline text-gray-800"
                            >
                              Administration
                            </Link>
                          )}
                          {!loggedIn && loginEnabled && (
                            <Link
                              to="/modern/login"
                              onClick={() => setGearsOpen(false)}
                              className="block px-4 py-1.5 text-sm hover:bg-[#edeae4] no-underline text-gray-800"
                            >
                              Login
                            </Link>
                          )}
                          {loggedIn && !layout.ldap && (
                            <Link
                              to="/modern/profile/change-password"
                              onClick={() => setGearsOpen(false)}
                              className="block px-4 py-1.5 text-sm hover:bg-[#edeae4] no-underline text-gray-800"
                            >
                              Change my password
                            </Link>
                          )}
                        </div>
                      )}
                    </div>
                  )}

                  {loggedIn && (
                    <button
                      type="button"
                      onClick={signOut}
                      className="p-3 text-white hover:[text-shadow:0_0_10px_#fff]"
                      title="Sign out"
                    >
                      <LogOut className="h-5 w-5" />
                    </button>
                  )}
                </div>

                {/* Mobile toggle */}
                <button
                  type="button"
                  aria-label="Toggle navigation"
                  aria-expanded={mobileOpen}
                  className="md:hidden p-2 text-white"
                  onClick={() => setMobileOpen(!mobileOpen)}
                >
                  {mobileOpen ? <X className="h-5 w-5" /> : <Menu className="h-5 w-5" />}
                </button>
              </>
            )}
          </div>

          {/* Mobile nav */}
          {!hideNavbar && mobileOpen && (
            <div className="md:hidden pb-3 border-t border-white/20">
              <div className="flex flex-col pt-2">
                {navItem('Transactions', 'transaction', 'transaction/average', queryString(true, true), layout.showNavbarTransaction)}
                {navItem('Errors', 'error', 'error/messages', queryString(true, true), layout.showNavbarError)}
                {navItem('JVM', 'jvm', 'jvm/gauges', queryString(false, false, true), layout.showNavbarJvm)}
                {layout.central && navItem('Synthetic', 'syntheticMonitor', 'synthetic-monitors', queryString(true, false), layout.showNavbarSyntheticMonitor)}
                {navItem('Incidents', 'incidents', 'incidents', '', layout.showNavbarIncident)}
                {navItem('Reporting', 'report', 'report/ad-hoc', reportQueryString(), layout.showNavbarReport)}
                {layout.showNavbarConfig && (
                  <Link
                    to={configUrl()}
                    onClick={() => setMobileOpen(false)}
                    className="px-3 py-2 text-sm text-white/70 hover:text-white no-underline"
                  >
                    Configuration
                  </Link>
                )}
                {layout.adminView && (
                  <Link
                    to="/modern/admin/general"
                    onClick={() => setMobileOpen(false)}
                    className="px-3 py-2 text-sm text-white/70 hover:text-white no-underline"
                  >
                    Administration
                  </Link>
                )}
                {!loggedIn && loginEnabled && (
                  <Link
                    to="/modern/login"
                    onClick={() => setMobileOpen(false)}
                    className="px-3 py-2 text-sm text-white/70 hover:text-white no-underline"
                  >
                    Login
                  </Link>
                )}
                {loggedIn && !layout.ldap && (
                  <Link
                    to="/modern/profile/change-password"
                    onClick={() => setMobileOpen(false)}
                    className="px-3 py-2 text-sm text-white/70 hover:text-white no-underline"
                  >
                    Change my password
                  </Link>
                )}
                {loggedIn && (
                  <button
                    type="button"
                    onClick={() => {
                      setMobileOpen(false)
                      signOut()
                    }}
                    className="px-3 py-2 text-left text-sm text-white/70 hover:text-white"
                  >
                    Sign out
                  </button>
                )}
                <a
                  href={classicHref}
                  className="px-3 py-2 text-sm text-white/50 hover:text-white/80"
                  target="_self"
                >
                  Classic UI
                </a>
              </div>
            </div>
          )}
        </div>
      </nav>
      {signOutMessage && (
        <div className="mx-auto max-w-7xl px-4 pt-2 text-center text-base font-semibold text-green-600">
          You have been signed out
        </div>
      )}
      {navbarErrorMessage && (
        <div className="mx-auto max-w-7xl px-4 pt-2 text-center text-base font-semibold text-red-600">
          {navbarErrorMessage}
        </div>
      )}
    </>
  )
}
