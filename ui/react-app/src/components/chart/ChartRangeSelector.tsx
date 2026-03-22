/*
 * ChartRangeSelector — Classic #gtChartRange dropdown menu and custom date range modal.
 *
 * Replaces ChartRangeCtrl / template/gt-chart-range.html.
 */

import { useEffect, useRef, useState } from 'react'
import { Button } from '../ui/button'
import { Input } from '../ui/input'
import { RANGE_SELECTIONS, formatLast, formatTimeRange } from '../../lib/chart-utils'
import { cn } from '../../lib/utils'
import type { ChartRange, ChartRangeActions } from '../../hooks/useChartRange'

interface ChartRangeSelectorProps {
  range: ChartRange
  actions: ChartRangeActions
}

export function ChartRangeSelector({
  range,
  actions,
}: ChartRangeSelectorProps) {
  const [open, setOpen] = useState(false)
  const [showCustom, setShowCustom] = useState(false)
  const [customFrom, setCustomFrom] = useState('')
  const [customTo, setCustomTo] = useState('')
  const dropdownRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    function handleOutsideMouseDown(e: MouseEvent) {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target as Node)) {
        setOpen(false)
      }
    }
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', handleOutsideMouseDown)
    document.addEventListener('keydown', handleKeyDown)
    return () => {
      document.removeEventListener('mousedown', handleOutsideMouseDown)
      document.removeEventListener('keydown', handleKeyDown)
    }
  }, [open])

  useEffect(() => {
    if (!showCustom) return
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        setShowCustom(false)
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [showCustom])

  const display = range.last
    ? formatLast(range.last)
    : formatTimeRange(range.chartFrom, range.chartTo)

  function handleRangeClick(last: number) {
    if (last === range.last) {
      actions.refresh()
    } else {
      actions.setLast(last)
    }
  }

  function openCustomRange() {
    const fromDate = new Date(range.chartFrom)
    const toDate = new Date(range.chartTo)
    setCustomFrom(formatDateTimeLocal(fromDate))
    setCustomTo(formatDateTimeLocal(toDate))
    setShowCustom(true)
  }

  function applyCustomRange() {
    const from = new Date(customFrom).getTime()
    const to = new Date(customTo).getTime()
    if (!isNaN(from) && !isNaN(to) && to > from) {
      actions.setCustomRange(from, to)
      setShowCustom(false)
    }
  }

  return (
    <>
      <div ref={dropdownRef} className="relative inline-block">
        <button
          type="button"
          id="gtChartRange"
          aria-haspopup="true"
          aria-expanded={open}
          onClick={() => setOpen((prev) => !prev)}
          className={cn(
            'inline-flex items-center gap-2.5 rounded-lg px-4 py-1 text-[1.5rem] font-semibold leading-tight text-[#505050] transition-colors focus:outline-none focus-visible:ring-3 focus-visible:ring-[#417998]/25',
            open ? 'bg-[#d9d4c7]' : 'bg-[#edeae4] hover:bg-[#e2ddd2]'
          )}
        >
          <span>{display}</span>
          <span className="inline-block h-0 w-0 shrink-0 border-x-[5px] border-x-transparent border-t-[5px] border-t-[#505050]" />
        </button>

        {open && (
          <div className="absolute right-0 top-full z-50 mt-1 min-w-full whitespace-nowrap rounded-lg border border-black/15 bg-white py-1.5 shadow-md">
            {RANGE_SELECTIONS.map((last) => (
              <button
                key={last}
                type="button"
                onClick={() => {
                  setOpen(false)
                  handleRangeClick(last)
                }}
                className={cn(
                  'block w-full px-5 py-1 text-left text-[1.25rem] text-[#212529] hover:bg-[#edeae4]',
                  range.last === last && 'bg-[#edeae4] font-semibold'
                )}
              >
                {formatLast(last)}
              </button>
            ))}
            <button
              type="button"
              onClick={() => {
                setOpen(false)
                openCustomRange()
              }}
              className="block w-full px-5 py-1 text-left text-[1.25rem] text-[#212529] hover:bg-[#edeae4]"
            >
              Custom range...
            </button>
          </div>
        )}
      </div>

      {/* Custom date range modal */}
      {showCustom && (
        <div
          id="customDateRangeModal"
          role="dialog"
          aria-modal="true"
          aria-labelledby="customDateRangeModalTitle"
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4"
          onMouseDown={(e) => {
            if (e.target === e.currentTarget) setShowCustom(false)
          }}
        >
          <div className="w-full max-w-[500px] rounded-lg border border-black/20 bg-white shadow-xl">
            <div className="flex items-center justify-between border-b border-gray-200 px-6 py-4">
              <h4 id="customDateRangeModalTitle" className="text-xl font-semibold text-gray-800">
                Custom date range
              </h4>
              <button
                type="button"
                aria-label="Close"
                onClick={() => setShowCustom(false)}
                className="text-xl leading-none text-gray-500 hover:text-gray-800"
              >
                &times;
              </button>
            </div>
            <div className="space-y-4 px-6 py-6">
              <div>
                <label className="mb-1 block text-sm font-bold text-gray-700">From</label>
                <Input
                  type="datetime-local"
                  value={customFrom}
                  onChange={(e) => setCustomFrom(e.target.value)}
                  className="w-full text-sm"
                />
              </div>
              <div>
                <label className="mb-1 block text-sm font-bold text-gray-700">To</label>
                <Input
                  type="datetime-local"
                  value={customTo}
                  onChange={(e) => setCustomTo(e.target.value)}
                  className="w-full text-sm"
                />
              </div>
            </div>
            <div className="flex justify-end gap-2 border-t border-gray-200 px-6 py-3">
              <Button variant="outline" size="sm" onClick={() => setShowCustom(false)}>
                Cancel
              </Button>
              <Button size="sm" onClick={applyCustomRange}>
                Apply
              </Button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}

function formatDateTimeLocal(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}
