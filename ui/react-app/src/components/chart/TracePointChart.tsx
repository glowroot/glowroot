/*
 * TracePointChart — scatter plot for individual trace execution points.
 *
 * Replaces the Flot scatter chart from traces.js.
 *
 * Three series: normal (blue), error (red), partial/active (yellow).
 * Click on a point to open trace detail.
 */

import { useEffect, useId, useMemo, useRef, useCallback, useState } from 'react'
import * as d3 from 'd3'

export interface TracePoint {
  /** [captureTime_ms, durationMs, agentId, traceId, checkLiveTraces?] */
  captureTime: number
  durationMs: number
  agentId: string
  traceId: string
  checkLiveTraces?: boolean
  error?: boolean
  partial?: boolean
}

export interface TracePointChartProps {
  normalPoints: TracePoint[]
  errorPoints: TracePoint[]
  partialPoints: TracePoint[]
  chartFrom: number
  chartTo: number
  /** Y-axis bounds (for duration filtering) */
  durationLow?: number
  durationHigh?: number
  /** Chart height (default: 300) */
  height?: number
  /** Called on zoom */
  onZoom?: (from: number, to: number, zoomingOut: boolean) => void
  /** Called on selection (2D xory brush) */
  onSelection?: (from: number, to: number, durationLow?: number, durationHigh?: number) => void
  /** Called when a trace point is clicked */
  onPointClick?: (point: TracePoint, ctrlKey: boolean) => void
  /** Trace ID of the currently highlighted point */
  highlightedTraceId?: string | null
}

const MARGIN = { top: 10, right: 20, bottom: 30, left: 60 }
const NORMAL_COLOR = '#edc240'
const ERROR_COLOR = '#bf380b'
const PARTIAL_COLOR = '#417998'

export function TracePointChart({
  normalPoints,
  errorPoints,
  partialPoints,
  chartFrom,
  chartTo,
  durationLow,
  durationHigh,
  height = 300,
  onZoom,
  onSelection,
  onPointClick,
  highlightedTraceId,
}: TracePointChartProps) {
  const clipId = `trace-clip-${useId().replace(/:/g, '')}`
  const containerRef = useRef<HTMLDivElement>(null)
  const svgRef = useRef<SVGSVGElement>(null)
  const tooltipRef = useRef<HTMLDivElement>(null)
  const [containerWidth, setContainerWidth] = useState(0)

  // Track container width
  useEffect(() => {
    const el = containerRef.current
    if (!el) return
    const obs = new ResizeObserver((entries) => {
      for (const entry of entries) {
        setContainerWidth(entry.contentRect.width)
      }
    })
    obs.observe(el)
    setContainerWidth(el.clientWidth)
    return () => obs.disconnect()
  }, [])

  const allPoints = useMemo(
    () => [...normalPoints, ...errorPoints, ...partialPoints],
    [normalPoints, errorPoints, partialPoints]
  )

  const renderChart = useCallback(() => {
    const svg = svgRef.current
    if (!svg || containerWidth === 0) return

    const width = containerWidth - MARGIN.left - MARGIN.right
    const chartHeight = height - MARGIN.top - MARGIN.bottom
    if (width <= 0 || chartHeight <= 0) return

    d3.select(svg).selectAll('*').remove()
    if (tooltipRef.current) {
      tooltipRef.current.style.display = 'none'
    }

    const g = d3
      .select(svg)
      .attr('width', containerWidth)
      .attr('height', height)
      .append('g')
      .attr('transform', `translate(${MARGIN.left},${MARGIN.top})`)

    // Scales
    const xScale = d3.scaleTime().domain([chartFrom, chartTo]).range([0, width])

    const yMin = durationLow ?? 0
    let yMax = durationHigh ?? 0
    if (durationHigh === undefined) {
      for (const p of allPoints) {
        if (p.durationMs > yMax) yMax = p.durationMs
      }
      if (yMax <= yMin) {
        yMax = yMin + 10
      } else {
        yMax = yMin + (yMax - yMin) * 1.1
      }
    } else if (yMax <= yMin) {
      yMax = yMin + 10
    }

    const yScale = d3.scaleLinear().domain([yMin, yMax]).nice().range([chartHeight, 0])

    // Axes
    g.append('g')
      .attr('transform', `translate(0,${chartHeight})`)
      .call(
        d3
          .axisBottom(xScale)
          .ticks(Math.max(2, Math.floor(width / 120)))
          .tickSizeOuter(0)
      )
      .selectAll('text')
      .style('font-size', '11px')

    g.append('g')
      .call(
        d3
          .axisLeft(yScale)
          .ticks(Math.max(2, Math.floor(chartHeight / 40)))
          .tickFormat((d) => `${d}`)
          .tickSizeOuter(0)
      )
      .selectAll('text')
      .style('font-size', '11px')

    // Y-axis label
    g.append('text')
      .attr('transform', 'rotate(-90)')
      .attr('y', -MARGIN.left + 12)
      .attr('x', -chartHeight / 2)
      .attr('text-anchor', 'middle')
      .style('font-size', '11px')
      .style('fill', '#666')
      .text('milliseconds')

    // Grid
    g.append('g')
      .selectAll('line')
      .data(yScale.ticks(Math.floor(chartHeight / 40)))
      .enter()
      .append('line')
      .attr('x1', 0)
      .attr('x2', width)
      .attr('y1', (d) => yScale(d))
      .attr('y2', (d) => yScale(d))
      .attr('stroke', '#e8e8e8')
      .attr('stroke-dasharray', '2,2')

    // Brush appended BEFORE chartArea so trace points receive click and hover events above overlay
    const brush = d3
      .brush()
      .extent([
        [0, 0],
        [width, chartHeight],
      ])
      .on('end', (event: d3.D3BrushEvent<unknown>) => {
        if (!event.selection) return
        const [[x0, y0], [x1, y1]] = event.selection as [[number, number], [number, number]]
        g.select<SVGGElement>('.brush').call(brush.move, null)
        if (Math.abs(x1 - x0) < 3 && Math.abs(y1 - y0) < 3) return
        const from = Math.max(chartFrom, Math.round(xScale.invert(x0).getTime()))
        const to = Math.min(chartTo, Math.round(xScale.invert(x1).getTime()))
        const rawLow = Math.max(0, Math.round(yScale.invert(y1)))
        const rawHigh = y0 <= 1 ? undefined : Math.round(yScale.invert(y0))
        if (from < to) {
          onSelection?.(from, to, rawLow, rawHigh)
        }
      })

    const brushGroup = g.append('g').attr('class', 'brush').call(brush)

    // Ctrl+scroll zoom & double-click zoom — attach to brush overlay
    brushGroup
      .select<SVGRectElement>('.overlay')
      .style('cursor', 'crosshair')
      .on('wheel.zoom', function (event: WheelEvent) {
        if (!event.ctrlKey) return
        event.preventDefault()
        const zoomingOut = event.deltaY > 0
        const [mx] = d3.pointer(event, g.node())
        const xTime = xScale.invert(mx).getTime()
        const factor = zoomingOut ? 2 : 0.5
        onZoom?.(
          xTime - (xTime - chartFrom) * factor,
          xTime + (chartTo - xTime) * factor,
          zoomingOut
        )
      })
      .on('dblclick.zoom', function (event: MouseEvent) {
        event.preventDefault()
        const [mx] = d3.pointer(event, g.node())
        const xTime = xScale.invert(mx).getTime()
        const range = chartTo - chartFrom
        onZoom?.(xTime - range / 4, xTime + range / 4, false)
      })

    // Clip
    g.append('defs')
      .append('clipPath')
      .attr('id', clipId)
      .append('rect')
      .attr('width', width)
      .attr('height', chartHeight)

    const chartArea = g.append('g').attr('clip-path', `url(#${clipId})`)
    const tooltip = tooltipRef.current

    function drawPoints(
      points: TracePoint[],
      color: string,
      shape: 'circle' | 'triangle' | 'diamond'
    ) {
      const group = chartArea.selectAll<SVGElement, TracePoint>('.trace-point-' + shape)
        .data(points)
        .enter()

      const isHighlighted = (d: TracePoint) =>
        Boolean(highlightedTraceId && d.traceId === highlightedTraceId)

      let elements: d3.Selection<SVGElement, TracePoint, SVGGElement, unknown>
      if (shape === 'circle') {
        elements = group
          .append('circle')
          .attr('cx', (d) => xScale(d.captureTime))
          .attr('cy', (d) => yScale(d.durationMs))
          .attr('r', (d) => (isHighlighted(d) ? 6 : 4)) as unknown as d3.Selection<
            SVGElement,
            TracePoint,
            SVGGElement,
            unknown
          >
      } else {
        const symbolType = shape === 'triangle' ? d3.symbolTriangle : d3.symbolDiamond
        elements = group
          .append('path')
          .attr('d', (d) =>
            d3
              .symbol()
              .type(symbolType)
              .size(isHighlighted(d) ? 90 : 50)() || ''
          )
          .attr(
            'transform',
            (d) => `translate(${xScale(d.captureTime)},${yScale(d.durationMs)})`
          ) as unknown as d3.Selection<SVGElement, TracePoint, SVGGElement, unknown>
      }

      elements
        .attr('class', 'trace-point-' + shape)
        .attr('fill', color)
        .attr('fill-opacity', (d) => (isHighlighted(d) ? 1 : 0.7))
        .attr('stroke', (d) => (isHighlighted(d) ? '#1f2937' : color))
        .attr('stroke-width', (d) => (isHighlighted(d) ? 2.5 : 1))
        .attr('cursor', 'pointer')
        .on('click', (event: MouseEvent, d) =>
          onPointClick?.(d, Boolean(event.ctrlKey || event.metaKey))
        )
        .on('mouseenter', (event: MouseEvent, d) => {
          if (tooltip) {
            tooltip.innerHTML = `<div><b>${d.durationMs.toLocaleString(undefined, { maximumFractionDigits: 1 })} ms</b></div><div class="text-gray-500">${new Date(d.captureTime).toLocaleTimeString()}</div>`
            tooltip.style.display = 'block'
            tooltip.style.left = `${event.clientX + 10}px`
            tooltip.style.top = `${event.clientY - 10}px`
          }
        })
        .on('mouseleave', () => {
          if (tooltip) tooltip.style.display = 'none'
        })
    }

    // Draw in order: normal (bottom), error, partial (top)
    drawPoints(normalPoints, NORMAL_COLOR, 'circle')
    drawPoints(errorPoints, ERROR_COLOR, 'triangle')
    drawPoints(partialPoints, PARTIAL_COLOR, 'diamond')
  }, [
    clipId,
    containerWidth,
    height,
    normalPoints,
    errorPoints,
    partialPoints,
    allPoints,
    chartFrom,
    chartTo,
    durationLow,
    durationHigh,
    onZoom,
    onSelection,
    onPointClick,
    highlightedTraceId,
  ])

  useEffect(() => {
    renderChart()
  }, [renderChart])

  return (
    <div ref={containerRef} className="relative w-full">
      <svg ref={svgRef} />
      <div
        ref={tooltipRef}
        className="fixed z-50 pointer-events-none rounded border border-gray-300 bg-white px-2 py-1 shadow-md text-xs"
        style={{ display: 'none' }}
      />
      {/* Legend */}
      <div className="flex gap-4 mt-2 px-2 text-xs">
        {normalPoints.length > 0 && (
          <span className="flex items-center gap-1">
            <span className="inline-block w-3 h-3 rounded-full" style={{ background: NORMAL_COLOR }} />
            Normal
          </span>
        )}
        {errorPoints.length > 0 && (
          <span className="flex items-center gap-1">
            <span className="inline-block w-0 h-0 border-l-[6px] border-r-[6px] border-b-[10px] border-l-transparent border-r-transparent" style={{ borderBottomColor: ERROR_COLOR }} />
            Error
          </span>
        )}
        {partialPoints.length > 0 && (
          <span className="flex items-center gap-1">
            <span className="inline-block w-3 h-3 rotate-45" style={{ background: PARTIAL_COLOR }} />
            Active/Partial
          </span>
        )}
      </div>
    </div>
  )
}
