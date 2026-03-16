import { useEffect, useRef } from 'react'
import { useBlocker, type BlockerFunction } from 'react-router-dom'

export interface UseConfirmIfHasChangesOptions {
  /** Whether the current form has unsaved changes */
  hasChanges: boolean
  /**
   * Ref that can be set to true right before a programmatic save/delete
   * triggers navigate(), preventing a false positive prompt before React
   * re-renders with hasChanges=false.
   */
  bypassRef?: { current: boolean }
}

export interface UseConfirmIfHasChangesResult {
  isBlocked: boolean
  confirmNavigation: () => void
  cancelNavigation: () => void
  bypassRef: { current: boolean }
}

/**
 * Guards against losing unsaved form changes on both in-app React Router
 * navigation (via useBlocker) and browser tab close / reload (via beforeunload).
 * Matches Classic Glowroot's confirmIfHasChanges service.
 */
export function useConfirmIfHasChanges({
  hasChanges,
  bypassRef: externalBypassRef,
}: UseConfirmIfHasChangesOptions): UseConfirmIfHasChangesResult {
  const internalBypassRef = useRef(false)
  const bypassRef = externalBypassRef ?? internalBypassRef
  const hasChangesRef = useRef(hasChanges)
  hasChangesRef.current = hasChanges

  const shouldBlock: BlockerFunction = ({ currentLocation, nextLocation }) => {
    if (bypassRef.current) return false
    if (!hasChangesRef.current) return false
    return (
      currentLocation.pathname !== nextLocation.pathname ||
      currentLocation.search !== nextLocation.search
    )
  }

  const blocker = useBlocker(shouldBlock)

  useEffect(() => {
    if (blocker.state === 'blocked' && !hasChanges) {
      blocker.reset()
    }
  }, [blocker, hasChanges])

  useEffect(() => {
    if (!hasChanges) return
    function handleBeforeUnload(event: BeforeUnloadEvent) {
      if (bypassRef.current) return
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', handleBeforeUnload)
    return () => {
      window.removeEventListener('beforeunload', handleBeforeUnload)
    }
  }, [hasChanges, bypassRef])

  return {
    isBlocked: blocker.state === 'blocked',
    confirmNavigation: () => {
      if (blocker.state === 'blocked') {
        blocker.proceed()
      }
    },
    cancelNavigation: () => {
      if (blocker.state === 'blocked') {
        blocker.reset()
      }
    },
    bypassRef,
  }
}
