import { useEffect } from 'react'
import { Outlet } from 'react-router-dom'
import { Sidebar, type SidebarGroup } from '../../components/layout/Sidebar'
import { useLayout } from '../../contexts/LayoutContext'

export function ProfileLayout() {
  const { layout } = useLayout()

  useEffect(() => {
    document.title = 'Profile \u2014 Change password \u2014 Glowroot'
  }, [])

  const groups: SidebarGroup[] = [
    {
      items: [
        { label: 'Change password', href: '/modern/profile/change-password' },
      ],
    },
  ]

  return (
    <div>
      <div className="mb-4">
        {layout.embeddedAgentRollup?.lastDisplayPart ? (
          <h1 className="gt-header-text text-2xl font-bold text-[#8e6c15]">
            {layout.embeddedAgentRollup.lastDisplayPart}
            <span className="mx-2 text-gray-400">|</span>
            Profile
          </h1>
        ) : (
          <h1 className="gt-header-text text-2xl font-bold text-[#8e6c15]">Profile</h1>
        )}
      </div>
      <div className="flex gap-6">
        <Sidebar groups={groups} />
        <div className="min-w-0 flex-1">
          <Outlet />
        </div>
      </div>
    </div>
  )
}
