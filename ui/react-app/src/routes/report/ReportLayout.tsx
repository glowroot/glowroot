/*
 * Report layout shell with left sidebar navigation and main card frame.
 *
 * Replaces report.html.
 */

import { useEffect } from 'react'
import { Outlet } from 'react-router-dom'
import { useLayout } from '../../contexts/LayoutContext'
import { Sidebar, type SidebarGroup } from '../../components/layout/Sidebar'

export function ReportLayout() {
  const { layout } = useLayout()

  useEffect(() => {
    document.title = 'Reports \u00b7 Glowroot'
  }, [])

  const groups: SidebarGroup[] = [
    {
      items: [
        {
          label: 'Ad hoc report',
          href: '/modern/report/ad-hoc',
        },
      ],
    },
  ]

  const embeddedDisplay =
    layout.embeddedAgentRollup?.topLevelDisplay || layout.embeddedAgentRollup?.lastDisplayPart || ''

  return (
    <div>
      <div className="mb-4">
        {embeddedDisplay ? (
          <h1 className="gt-header-text text-2xl font-bold text-[#8e6c15]">
            {embeddedDisplay}
            <span className="mx-2 text-gray-400">|</span>
            Reporting
          </h1>
        ) : (
          <h1 className="gt-header-text text-2xl font-bold text-[#8e6c15]">Reporting</h1>
        )}
      </div>

      <div className="flex gap-6 items-start">
        <div className="print:hidden">
          <Sidebar groups={groups} />
        </div>
        <div className="flex-1 min-w-0 rounded-lg border border-[#d2ba7f] bg-white overflow-hidden">
          <div className="border-b border-[#d2ba7f] bg-[#edeae4] px-5 py-3 text-[#555]">
            <h2 className="text-lg font-semibold text-gray-900">Ad hoc report</h2>
          </div>
          <div className="p-5">
            <Outlet />
          </div>
        </div>
      </div>
    </div>
  )
}
