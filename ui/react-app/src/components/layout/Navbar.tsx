import { Link, useLocation } from 'react-router-dom'
import {
  Settings,
  LogOut,
  Menu,
  X,
} from 'lucide-react'
import { useState } from 'react'
import { useLayout } from '../../contexts/LayoutContext'
import { useAgent } from '../../contexts/AgentContext'
import { useAuth } from '../../contexts/AuthContext'
import { cn } from '../../lib/utils'

function classicPath(modernPath: string): string {
  // strip /modern prefix
  return modernPath.replace(/^\/modern/, '') || '/'
}

export function Navbar() {
  const { layout } = useLayout()
  const { agentQueryString } = useAgent()
  const { loggedIn, loginEnabled, signOut } = useAuth()
  const location = useLocation()
  const [mobileOpen, setMobileOpen] = useState(false)

  const qs = agentQueryString()
  const currentSection = location.pathname.split('/')[2] // after /modern/

  function navItem(
    label: string,
    section: string,
    path: string,
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
          'flex items-center px-3 py-2 text-sm font-medium transition-colors',
          'hover:text-white',
          active ? 'text-white border-b-2 border-white' : 'text-white/70'
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

  return (
    <nav className="bg-[#3c3f42] text-white">
      <div className="mx-auto max-w-7xl px-4">
        <div className="flex h-11 items-center justify-between">
          {/* Brand */}
          <Link
            to="/modern/"
            className="text-lg font-semibold text-white mr-5 no-underline"
          >
            Glowroot
          </Link>

          {/* Desktop nav */}
          <div className="hidden md:flex flex-1 items-center gap-0.5">
            {navItem('Transactions', 'transaction', 'transaction/average', layout.showNavbarTransaction)}
            {navItem('Errors', 'error', 'error/messages', layout.showNavbarError)}
            {navItem('JVM', 'jvm', 'jvm/gauges', layout.showNavbarJvm)}
            {layout.central && navItem('Synthetic', 'syntheticMonitor', 'synthetic-monitors', layout.showNavbarSyntheticMonitor)}
            {navItem('Incidents', 'incidents', 'incidents', layout.showNavbarIncident)}
            {navItem('Reporting', 'report', 'report/ad-hoc', layout.showNavbarReport)}
          </div>

          {/* Right side */}
          <div className="hidden md:flex items-center gap-1">
            {/* Classic UI link */}
            <a
              href={classicPath(location.pathname + location.search)}
              className="px-2 py-1 text-xs text-white/50 hover:text-white/80 transition-colors"
              target="_self"
            >
              Classic UI
            </a>

            {showGears && (
              <div className="relative group">
                <button
                  className="p-2 text-white/70 hover:text-white"
                  aria-label="Settings"
                >
                  <Settings className="h-5 w-5" />
                </button>
                <div className="absolute right-0 top-full z-50 hidden group-hover:block w-48 bg-white text-gray-800 rounded shadow-lg border py-1">
                  {layout.showNavbarConfig && (
                    <Link
                      to={'/modern/config/general' + qs}
                      className="block px-4 py-1.5 text-sm hover:bg-gray-100 no-underline text-gray-800"
                    >
                      Configuration
                    </Link>
                  )}
                  {layout.adminView && (
                    <Link
                      to="/modern/admin/general"
                      className="block px-4 py-1.5 text-sm hover:bg-gray-100 no-underline text-gray-800"
                    >
                      Administration
                    </Link>
                  )}
                  {!loggedIn && loginEnabled && (
                    <Link
                      to="/modern/login"
                      className="block px-4 py-1.5 text-sm hover:bg-gray-100 no-underline text-gray-800"
                    >
                      Login
                    </Link>
                  )}
                  {loggedIn && !layout.ldap && (
                    <Link
                      to="/modern/profile/change-password"
                      className="block px-4 py-1.5 text-sm hover:bg-gray-100 no-underline text-gray-800"
                    >
                      Change my password
                    </Link>
                  )}
                </div>
              </div>
            )}

            {loggedIn && (
              <button
                onClick={signOut}
                className="p-2 text-white/70 hover:text-white"
                title="Sign out"
              >
                <LogOut className="h-5 w-5" />
              </button>
            )}
          </div>

          {/* Mobile toggle */}
          <button
            className="md:hidden p-2 text-white"
            onClick={() => setMobileOpen(!mobileOpen)}
          >
            {mobileOpen ? <X className="h-5 w-5" /> : <Menu className="h-5 w-5" />}
          </button>
        </div>

        {/* Mobile nav */}
        {mobileOpen && (
          <div className="md:hidden pb-3 border-t border-white/20">
            <div className="flex flex-col pt-2">
              {navItem('Transactions', 'transaction', 'transaction/average', layout.showNavbarTransaction)}
              {navItem('Errors', 'error', 'error/messages', layout.showNavbarError)}
              {navItem('JVM', 'jvm', 'jvm/gauges', layout.showNavbarJvm)}
              {layout.central && navItem('Synthetic', 'syntheticMonitor', 'synthetic-monitors', layout.showNavbarSyntheticMonitor)}
              {navItem('Incidents', 'incidents', 'incidents', layout.showNavbarIncident)}
              {navItem('Reporting', 'report', 'report/ad-hoc', layout.showNavbarReport)}
              <a
                href={classicPath(location.pathname + location.search)}
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
  )
}
