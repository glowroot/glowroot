import { useLayout } from '../../contexts/LayoutContext'
import { useAgent } from '../../contexts/AgentContext'

export function Footer() {
  const { layout } = useLayout()
  const { agentRollup, agentId, isRollup } = useAgent()

  return (
    <footer className="mt-auto border-t border-[#d2ba7f] bg-[#edeae4] py-2.5 text-center text-[11px] text-[#555]">
      {layout.central ? (
        <>
          <div>Central collector version {layout.glowrootVersion}</div>
          {agentRollup && !isRollup && !!agentId && (
            <div>Agent version {agentRollup.glowrootVersion}</div>
          )}
        </>
      ) : (
        <div>Glowroot version {layout.glowrootVersion}</div>
      )}
    </footer>
  )
}
