import { Link, useLocation } from 'react-router-dom'
import { cn } from '../../lib/utils'

export interface SidebarItem {
  label: string
  href: string
  /** Additional text shown on the right side (e.g. count) */
  rightText?: string
  /** Custom active state check — if omitted, checks pathname match */
  active?: boolean
  /** Hide this item */
  hidden?: boolean
}

export interface SidebarGroup {
  /** Optional caption above the group (Access, Server, …) */
  label?: string
  items: SidebarItem[]
}

interface SidebarProps {
  groups: SidebarGroup[]
}

export function Sidebar({ groups }: SidebarProps) {
  const location = useLocation()

  return (
    <div className="w-52 shrink-0 print:hidden space-y-4">
      {groups.map((group, gi) => {
        const visible = group.items.filter((item) => !item.hidden)
        if (visible.length === 0) return null
        return (
          <div key={gi}>
            {group.label && (
              <div className="mb-1.5 px-1 text-[11px] font-semibold uppercase tracking-wide text-gray-400">
                {group.label}
              </div>
            )}
            <div className="gt-sidebar rounded-md border border-[#e4d4a8] bg-white overflow-hidden">
              <div className="flex flex-col">
                {visible.map((item) => {
                  const active =
                    item.active !== undefined
                      ? item.active
                      : location.pathname === item.href ||
                        location.pathname + location.search === item.href
                  return (
                    <Link
                      key={item.href}
                      to={item.href}
                      className={cn(
                        'flex items-center justify-between px-3 py-2 text-sm no-underline',
                        'border-b border-gray-100 last:border-b-0 transition-colors',
                        active
                          ? 'bg-[#f5f0e6] text-gray-900 font-semibold border-l-2 border-l-[#c4a35a] pl-[10px]'
                          : 'text-[#555] hover:bg-[#faf8f4] border-l-2 border-l-transparent'
                      )}
                    >
                      <span className="break-all">{item.label}</span>
                      {item.rightText && (
                        <span className="ml-3 whitespace-nowrap text-xs text-gray-500">
                          {item.rightText}
                        </span>
                      )}
                    </Link>
                  )
                })}
              </div>
            </div>
          </div>
        )
      })}
    </div>
  )
}
