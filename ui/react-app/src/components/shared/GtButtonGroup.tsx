import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Button } from '../ui/button'
import { Dialog, DialogFooter, DialogTitle } from '../ui/dialog'
import { cn } from '../../lib/utils'
import { ApiError } from '../../lib/api'
import { useConfirmIfHasChanges } from '../../hooks/useConfirmIfHasChanges'

function formatSaveError(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status === 412) {
      return 'Someone else has updated the configuration on this page since you loaded it. Please reload the page and try again.'
    }
    if (e.status === 403 && (!e.message || e.message === 'HTTP 403')) {
      return 'You do not have permission to perform this action'
    }
    if (e.status === 0) {
      return 'Unable to connect to server'
    }
  }
  return e instanceof Error ? e.message : 'An error occurred'
}

interface GtButtonGroupProps {
  /** Save handler — should throw on error, or return false if cancelled */
  onSave?: () => Promise<void | false>
  /** Delete handler — should throw on error */
  onDelete?: () => Promise<void>
  /** Whether the form has unsaved changes */
  hasChanges?: boolean
  /** Additional explicit disable condition for the save button */
  saveDisabled?: boolean
  /** Custom synchronous validator run before calling onSave */
  validate?: () => boolean | string
  /** Label for the save button */
  saveLabel?: string
  /** Optional message shown on successful save (defaults to 'Added' when saveLabel is 'Add', otherwise 'Saved') */
  saveSuccessText?: string
  /** Label for the delete button */
  deleteLabel?: string
  /** Header shown in delete confirmation dialog */
  deleteConfirmHeader?: string
  /** Confirm message shown before delete */
  deleteConfirmMessage?: string
  /** Whether to show the delete button */
  showDelete?: boolean
  /** Extra buttons (e.g. test button) */
  children?: ReactNode
}

export function GtButtonGroup({
  onSave,
  onDelete,
  hasChanges,
  saveDisabled,
  validate,
  saveLabel = 'Save changes',
  saveSuccessText,
  deleteLabel = 'Delete',
  deleteConfirmHeader = 'Confirm delete',
  deleteConfirmMessage = 'Are you sure you want to delete?',
  showDelete = false,
  children,
}: GtButtonGroupProps) {
  const rootRef = useRef<HTMLDivElement>(null)
  const bypassUnsavedGuardRef = useRef(false)
  const [saving, setSaving] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [showDeleteModal, setShowDeleteModal] = useState(false)
  const [successMessage, setSuccessMessage] = useState<string | null>(null)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const [deleteError, setDeleteError] = useState<string | null>(null)

  const { isBlocked, confirmNavigation, cancelNavigation } = useConfirmIfHasChanges({
    hasChanges: hasChanges === true,
    bypassRef: bypassUnsavedGuardRef,
  })

  function getFormContainer(): HTMLElement | null {
    return rootRef.current?.closest('form') ?? rootRef.current?.parentElement ?? null
  }

  async function handleSave() {
    if (!onSave) return
    const formContainer = getFormContainer()
    formContainer?.classList.add('was-validated')

    if (formContainer?.querySelector('[data-gt-invalid]')) {
      setSuccessMessage(null)
      setErrorMessage('See validation error(s) above')
      return
    }

    if (validate) {
      const validationResult = validate()
      if (validationResult === false) {
        setSuccessMessage(null)
        setErrorMessage('See validation error(s) above')
        return
      }
      if (typeof validationResult === 'string') {
        setSuccessMessage(null)
        setErrorMessage(validationResult)
        return
      }
    }

    setSaving(true)
    setSuccessMessage(null)
    setErrorMessage(null)
    bypassUnsavedGuardRef.current = true
    try {
      const result = await onSave()
      if (result === false) {
        bypassUnsavedGuardRef.current = false
        return
      }
      formContainer?.classList.remove('was-validated')
      setSuccessMessage(saveSuccessText ?? (saveLabel === 'Add' ? 'Added' : 'Saved'))
      setTimeout(() => setSuccessMessage(null), 3000)
      setTimeout(() => {
        bypassUnsavedGuardRef.current = false
      }, 0)
    } catch (e) {
      bypassUnsavedGuardRef.current = false
      setErrorMessage(formatSaveError(e))
    } finally {
      setSaving(false)
    }
  }

  function handleDeleteClick() {
    if (!onDelete) return
    setDeleteError(null)
    setShowDeleteModal(true)
  }

  async function confirmDelete() {
    if (!onDelete) return
    setDeleting(true)
    setSuccessMessage(null)
    setErrorMessage(null)
    setDeleteError(null)
    bypassUnsavedGuardRef.current = true
    try {
      await onDelete()
      setShowDeleteModal(false)
      setTimeout(() => {
        bypassUnsavedGuardRef.current = false
      }, 0)
    } catch (e) {
      bypassUnsavedGuardRef.current = false
      const msg = formatSaveError(e)
      setDeleteError(msg)
      setErrorMessage(msg)
    } finally {
      setDeleting(false)
    }
  }

  const prevHasChangesRef = useRef(hasChanges)
  useEffect(() => {
    if (prevHasChangesRef.current === true && hasChanges === false) {
      getFormContainer()?.classList.remove('was-validated')
    }
    prevHasChangesRef.current = hasChanges
  }, [hasChanges])

  // First-input autofocus on form mount (matches Classic gtFormAutofocus)
  useEffect(() => {
    const formContainer = getFormContainer()
    if (!formContainer) return
    if (formContainer.contains(document.activeElement)) return
    const firstInput = formContainer.querySelector<HTMLElement>(
      'input:not([type="hidden"]):not([type="checkbox"]):not([type="radio"]):not([disabled]):not([readonly]), textarea:not([disabled]):not([readonly])',
    )
    firstInput?.focus()
  }, [])

  // Enter-to-submit inside form container
  const submitStateRef = useRef({
    handleSave,
    canSubmit: false,
  })
  submitStateRef.current = {
    handleSave,
    canSubmit:
      Boolean(onSave) &&
      hasChanges !== false &&
      saveDisabled !== true &&
      !saving &&
      !deleting,
  }

  useEffect(() => {
    const formContainer = getFormContainer()
    if (!formContainer) return
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key !== 'Enter') return
      if (
        event.defaultPrevented ||
        event.shiftKey ||
        event.ctrlKey ||
        event.metaKey ||
        event.altKey
      ) {
        return
      }
      const target = event.target as HTMLElement | null
      if (!target) return
      const tagName = target.tagName
      if (
        tagName === 'TEXTAREA' ||
        tagName === 'BUTTON' ||
        tagName === 'SELECT' ||
        tagName === 'A'
      ) {
        return
      }
      if (target.isContentEditable) return
      if (tagName === 'INPUT') {
        const inputType = (target as HTMLInputElement).type
        if (inputType === 'button' || inputType === 'submit' || inputType === 'reset') {
          return
        }
      }
      if (!submitStateRef.current.canSubmit) return
      event.preventDefault()
      void submitStateRef.current.handleSave()
    }
    formContainer.addEventListener('keydown', handleKeyDown)
    return () => {
      formContainer.removeEventListener('keydown', handleKeyDown)
    }
  }, [])

  return (
    <div ref={rootRef} className="flex items-center gap-3 pt-4">
      {onSave && (
        <Button
          onClick={handleSave}
          loading={saving}
          disabled={hasChanges === false || saveDisabled === true}
        >
          {saveLabel}
        </Button>
      )}
      {children}
      {showDelete && onDelete && (
        <Button
          variant="destructive"
          onClick={handleDeleteClick}
          loading={deleting}
        >
          {deleteLabel}
        </Button>
      )}
      {successMessage && (
        <span className={cn('text-sm font-medium text-green-600')}>
          {successMessage}
        </span>
      )}
      {errorMessage && (
        <span className={cn('text-sm font-medium text-red-600')}>
          {errorMessage}
        </span>
      )}

      <Dialog open={showDeleteModal} onClose={() => !deleting && setShowDeleteModal(false)}>
        <DialogTitle>{deleteConfirmHeader}</DialogTitle>
        <p className="mt-2 text-sm text-gray-600">{deleteConfirmMessage}</p>
        {deleteError && (
          <p className="mt-2 text-sm font-medium text-red-600">{deleteError}</p>
        )}
        <DialogFooter>
          <Button variant="destructive" onClick={confirmDelete} loading={deleting}>
            Yes
          </Button>
          <Button
            variant="outline"
            onClick={() => setShowDeleteModal(false)}
            disabled={deleting}
          >
            Cancel
          </Button>
        </DialogFooter>
      </Dialog>

      <Dialog open={isBlocked} onClose={cancelNavigation}>
        <DialogTitle>You have unsaved changes</DialogTitle>
        <p className="mt-2 text-sm text-gray-600">
          Are you sure you want to navigate away from this page?
        </p>
        <DialogFooter>
          <Button variant="outline" onClick={cancelNavigation}>
            Cancel
          </Button>
          <Button variant="default" onClick={confirmNavigation}>
            Yes
          </Button>
        </DialogFooter>
      </Dialog>
    </div>
  )
}
