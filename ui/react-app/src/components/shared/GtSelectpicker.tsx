import { useEffect, useMemo, useRef, useState } from 'react'
import { Check, ChevronDown, Search } from 'lucide-react'
import { cn } from '../../lib/utils'

export interface SelectpickerOption {
  value: string
  label: string
  disabled?: boolean
  indent?: number
}

export interface GtSelectpickerProps {
  options: SelectpickerOption[]
  multiple?: boolean
  value?: string
  values?: string[]
  onChange?: (value: string) => void
  onChangeMultiple?: (values: string[]) => void
  onOpen?: () => void
  placeholder?: string
  searchPlaceholder?: string
  showSelectAll?: boolean
  disabled?: boolean
  loading?: boolean
  emptyMessage?: string
  ariaLabel?: string
  className?: string
}

/**
 * Searchable single-select and filterable multi-select dropdown component
 * matching Classic Glowroot's `gtSelectpicker` / `gtMultiselect` directives.
 */
export function GtSelectpicker({
  options,
  multiple = false,
  value = '',
  values = [],
  onChange,
  onChangeMultiple,
  onOpen,
  placeholder = 'Select...',
  searchPlaceholder = 'Filter...',
  showSelectAll = true,
  disabled = false,
  loading = false,
  emptyMessage = 'No matching options',
  ariaLabel,
  className,
}: GtSelectpickerProps) {
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const containerRef = useRef<HTMLDivElement>(null)
  const searchInputRef = useRef<HTMLInputElement>(null)

  const filteredOptions = useMemo(() => {
    const trimmed = query.trim().toLowerCase()
    if (!trimmed) return options
    return options.filter((opt) => opt.label.toLowerCase().includes(trimmed))
  }, [options, query])

  const selectedSet = useMemo(() => new Set(values), [values])

  const selectableFiltered = useMemo(
    () => filteredOptions.filter((opt) => !opt.disabled),
    [filteredOptions],
  )

  const allFilteredSelected =
    selectableFiltered.length > 0 &&
    selectableFiltered.every((opt) => selectedSet.has(opt.value))

  useEffect(() => {
    if (!open) return
    onOpen?.()
    const timer = setTimeout(() => {
      searchInputRef.current?.focus()
    }, 0)

    function handleMouseDown(event: MouseEvent) {
      if (!containerRef.current?.contains(event.target as Node)) {
        setOpen(false)
      }
    }

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        setOpen(false)
      }
    }

    document.addEventListener('mousedown', handleMouseDown)
    document.addEventListener('keydown', handleKeyDown)
    return () => {
      clearTimeout(timer)
      document.removeEventListener('mousedown', handleMouseDown)
      document.removeEventListener('keydown', handleKeyDown)
    }
  }, [open]) // eslint-disable-line react-hooks/exhaustive-deps

  function toggleOpen() {
    if (disabled) return
    setOpen((prev) => {
      if (!prev) setQuery('')
      return !prev
    })
  }

  function handleOptionClick(opt: SelectpickerOption) {
    if (opt.disabled) return
    if (multiple) {
      const next = new Set(values)
      if (next.has(opt.value)) {
        next.delete(opt.value)
      } else {
        next.add(opt.value)
      }
      onChangeMultiple?.(Array.from(next))
    } else {
      onChange?.(opt.value)
      setOpen(false)
    }
  }

  function handleToggleSelectAll() {
    if (!multiple) return
    const next = new Set(values)
    if (allFilteredSelected) {
      for (const opt of selectableFiltered) {
        next.delete(opt.value)
      }
    } else {
      for (const opt of selectableFiltered) {
        next.add(opt.value)
      }
    }
    onChangeMultiple?.(Array.from(next))
  }

  let buttonText = placeholder
  if (multiple) {
    if (values.length > 0) {
      const selectedLabels = options
        .filter((opt) => selectedSet.has(opt.value))
        .map((opt) => opt.label)
      buttonText =
        selectedLabels.length <= 2
          ? selectedLabels.join(', ')
          : `${selectedLabels.length} selected`
    }
  } else if (value) {
    const match = options.find((opt) => opt.value === value)
    if (match) buttonText = match.label
  }

  return (
    <div ref={containerRef} className={cn('relative inline-block', className)}>
      <button
        type="button"
        aria-label={ariaLabel}
        aria-expanded={open}
        aria-haspopup="listbox"
        disabled={disabled}
        onClick={toggleOpen}
        className={cn(
          'flex h-8 w-full items-center justify-between gap-2 rounded-md border border-gray-300 bg-white px-2.5 text-sm text-gray-800 hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:cursor-not-allowed disabled:opacity-50',
        )}
      >
        <span className="truncate">{buttonText}</span>
        <ChevronDown className="h-3.5 w-3.5 shrink-0 text-gray-500" />
      </button>

      {open && (
        <div className="absolute left-0 top-full z-50 mt-1 min-w-full w-64 rounded-md border border-gray-200 bg-white shadow-lg">
          <div className="border-b border-gray-100 p-1.5">
            <div className="relative flex items-center">
              <Search className="pointer-events-none absolute left-2 h-3.5 w-3.5 text-gray-400" />
              <input
                ref={searchInputRef}
                type="text"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder={searchPlaceholder}
                aria-label="Filter options"
                className="h-7 w-full rounded border border-gray-300 bg-white pl-7 pr-2 text-xs text-gray-800 focus:border-blue-500 focus:outline-none"
              />
            </div>
          </div>

          {multiple && showSelectAll && selectableFiltered.length > 0 && (
            <button
              type="button"
              onClick={handleToggleSelectAll}
              className="flex w-full items-center gap-2 border-b border-gray-100 px-3 py-1.5 text-left text-xs font-medium text-blue-700 hover:bg-blue-50"
            >
              <span
                className={cn(
                  'inline-flex h-3.5 w-3.5 items-center justify-center rounded border',
                  allFilteredSelected
                    ? 'border-blue-600 bg-blue-600 text-white'
                    : 'border-gray-300 bg-white',
                )}
              >
                {allFilteredSelected && <Check className="h-2.5 w-2.5" />}
              </span>
              {allFilteredSelected ? 'Deselect all' : 'Select all'}
            </button>
          )}

          <div role="listbox" aria-multiselectable={multiple || undefined} className="max-h-60 overflow-y-auto py-1">
            {loading ? (
              <div className="px-3 py-2 text-xs text-gray-500">Loading...</div>
            ) : filteredOptions.length === 0 ? (
              <div className="px-3 py-2 text-xs text-gray-500">{emptyMessage}</div>
            ) : (
              filteredOptions.map((opt) => {
                const isSelected = multiple
                  ? selectedSet.has(opt.value)
                  : opt.value === value
                return (
                  <button
                    key={opt.value}
                    type="button"
                    role="option"
                    aria-selected={isSelected}
                    disabled={opt.disabled}
                    onClick={() => handleOptionClick(opt)}
                    style={
                      opt.indent
                        ? { paddingLeft: `${0.75 + opt.indent * 0.75}rem` }
                        : undefined
                    }
                    className={cn(
                      'flex w-full items-center gap-2 px-3 py-1.5 text-left text-xs',
                      opt.disabled
                        ? 'cursor-not-allowed text-gray-400'
                        : 'text-gray-800 hover:bg-gray-100',
                      isSelected && 'bg-blue-50 font-medium text-blue-800',
                    )}
                  >
                    <span
                      className={cn(
                        'inline-flex h-3.5 w-3.5 shrink-0 items-center justify-center',
                        multiple &&
                          'rounded border ' +
                            (isSelected
                              ? 'border-blue-600 bg-blue-600 text-white'
                              : 'border-gray-300 bg-white'),
                      )}
                    >
                      {isSelected && <Check className="h-2.5 w-2.5" />}
                    </span>
                    <span className="truncate">{opt.label}</span>
                  </button>
                )
              })
            )}
          </div>
        </div>
      )}
    </div>
  )
}
