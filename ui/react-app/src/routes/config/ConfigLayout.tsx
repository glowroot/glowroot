import { Outlet, useLocation } from 'react-router-dom'
import { AgentSelector } from '../../components/layout/AgentSelector'
import { Sidebar, type SidebarGroup } from '../../components/layout/Sidebar'
import { useLayout } from '../../contexts/LayoutContext'
import { useAgent } from '../../contexts/AgentContext'

export function ConfigLayout() {
  const { layout } = useLayout()
  const { agentRollupId, isRollup, agentQueryString } = useAgent()
  const location = useLocation()
  const path = location.pathname

  document.title = 'Configuration · Glowroot'

  // In central mode, an agent must be selected before any config page works
  const needsAgent = layout.central && !agentRollupId

  const qs = agentQueryString()

  // Classic item order; separate boxes (no shouty captions — groups are visual only).
  const groups: SidebarGroup[] = [
    {
      items: [
        {
          label: 'General',
          href: '/modern/config/general' + qs,
          hidden: !layout.central,
        },
      ],
    },
    {
      items: [
        {
          label: 'Transactions',
          href: '/modern/config/transaction' + qs,
          hidden: isRollup,
        },
        {
          label: 'Gauges',
          href: '/modern/config/gauge-list' + qs,
          active: path === '/modern/config/gauge-list' || path === '/modern/config/gauge',
          hidden: isRollup,
        },
        {
          label: 'JVM',
          href: '/modern/config/jvm' + qs,
          hidden: isRollup,
        },
      ],
    },
    {
      items: [
        {
          label: 'Synthetic monitors',
          href: '/modern/config/synthetic-monitor-list' + qs,
          active: path === '/modern/config/synthetic-monitor-list' || path === '/modern/config/synthetic-monitor',
          hidden: !layout.central,
        },
        {
          label: 'Alerts',
          href: '/modern/config/alert-list' + qs,
          active: path === '/modern/config/alert-list' || path === '/modern/config/alert',
        },
        {
          label: 'UI defaults',
          href: '/modern/config/ui-defaults' + qs,
        },
      ],
    },
    {
      items: [
        {
          label: 'Plugins',
          href: '/modern/config/plugin-list' + qs,
          active: path === '/modern/config/plugin-list' || path === '/modern/config/plugin',
          hidden: isRollup,
        },
        {
          label: 'Instrumentation',
          href: '/modern/config/instrumentation-list' + qs,
          active: path === '/modern/config/instrumentation-list' || path === '/modern/config/instrumentation',
          hidden: isRollup,
        },
      ],
    },
    {
      items: [
        {
          label: 'Advanced',
          href: '/modern/config/advanced' + qs,
        },
      ],
    },
    {
      items: [
        {
          label: 'config.json',
          href: '/modern/config/json' + qs,
          hidden: isRollup,
        },
      ],
    },
  ]

  const displayName = !layout.central
    ? layout.embeddedAgentRollup?.topLevelDisplay || layout.embeddedAgentRollup?.lastDisplayPart || ''
    : ''

  return (
    <div>
      <div className="gt-header pt-5 pb-12">
        <div className="gt-header-lhs flex flex-wrap items-center gap-1">
          {layout.central ? (
            <>
              <AgentSelector />
              <div
                aria-hidden="true"
                className="gt-header-text gt-separator mx-1.5 -mt-2 select-none text-[3.25rem] font-normal leading-none text-[#785b12]"
              >
                |
              </div>
              <h1 className="gt-header-text m-0 text-[2.25rem] font-bold leading-none text-[#785b12]">Configuration</h1>
            </>
          ) : displayName ? (
            <>
              <div className="gt-header-text text-[2.25rem] font-bold leading-none text-[#785b12]">
                {displayName}
              </div>
              <div
                aria-hidden="true"
                className="gt-header-text gt-separator mx-1.5 -mt-2 select-none text-[3.25rem] font-normal leading-none text-[#785b12]"
              >
                |
              </div>
              <h1 className="gt-header-text m-0 text-[2.25rem] font-bold leading-none text-[#785b12]">Configuration</h1>
            </>
          ) : (
            <h1 className="gt-header-text m-0 text-[2.25rem] font-bold leading-none text-[#785b12]">Configuration</h1>
          )}
        </div>
      </div>
      <div className="flex gap-8">
        {!needsAgent && <Sidebar groups={groups} />}
        <div className="flex-1 min-w-0">
          {needsAgent ? (
            <p className="text-sm text-gray-500">Please select an agent from the dropdown above.</p>
          ) : (
            <Outlet />
          )}
        </div>
      </div>
    </div>
  )
}
