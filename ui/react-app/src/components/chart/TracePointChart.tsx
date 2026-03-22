/*
 * TracePointChart — scatter plot for individual trace execution points.
 *
 * Replaces the Flot scatter chart from traces.js.
 *
 * Three series: normal (blue), error (red), partial/active (yellow).
 * Click on a point to open trace detail.
 */

import { useEffect, useRef, useCallback, useState } from 'react'
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
  /** Called on selection (brush) */
  onSelection?: (from: number, to: number) => void
  /** Called when a trace point is clicked */
  onPointClick?: (point: TracePoint) => void
}

const MARGIN = { top: 10, right: 20, bottom: 30, left: 60 }
const NORMAL_COLOR = '#5a8cc4'
const ERROR_COLOR = '#c44e4e'
const PARTIAL_COLOR = '#c4a84e'

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
}: TracePointChartProps) {
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

  const allPoints = [...normalPoints, ...errorPoints, ...partialPoints]

  const renderChart = useCallback(() => {
    const svg = svgRef.current
    if (!svg || containerWidth === 0) return

    const width = containerWidth - MARGIN.left - MARGIN.right
    const chartHeight = height - MARGIN.top - MARGIN.bottom
    if (width <= 0 || chartHeight <= 0) return

    d3.select(svg).selectAll('*').remove()

    const g = d3
      .select(svg)
      .attr('width', containerWidth)
      .attr('height', height)
      .append('g')
      .attr('transform', `translate(${MARGIN.left},${MARGIN.top})`)

    // Scales
    const xScale = d3.scaleTime().domain([chartFrom, chartTo]).range([0, width])

    let yMin = durationLow ?? 0
    let yMax = durationHigh ?? 0
    if (!durationHigh) {
      for (const p of allPoints) {
        if (p.durationMs > yMax) yMax = p.durationMs
      }
      if (yMax === 0) yMax = 10
      yMax *= 1.1
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

    // Clip
    g.append('defs')
      .append('clipPath')
      .attr('id', 'trace-clip')
      .append('rect')
      .attr('width', width)
      .attr('height', chartHeight)

    const chartArea = g.append('g').attr('clip-path', 'url(#trace-clip)')
    const tooltip = tooltipRef.current

    function drawPoints(
      points: TracePoint[],
      color: string,
      shape: 'circle' | 'triangle' | 'diamond'
    ) {
      const group = chartArea.selectAll<SVGElement, TracePoint>('.trace-point-' + shape)
        .data(points)
        .enter()

      if (shape === 'circle') {
        group
          .append('circle')
          .attr('cx', (d) => xScale(d.captureTime))
          .attr('cy', (d) => yScale(d.durationMs))
          .attr('r', 4)
          .attr('fill', color)
          .attr('fill-opacity', 0.7)
          .attr('stroke', color)
          .attr('stroke-width', 1)
          .attr('cursor', 'pointer')
          .on('click', (_event, d) => onPointClick?.(d))
          .on('mouseenter', (event, d) => {
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
      } else if (shape === 'triangle') {
        const tri = d3.symbol().type(d3.symbolTriangle).size(50)
        group
          .append('path')
          .attr('d', tri)
          .attr('transform', (d) => `translate(${xScale(d.captureTime)},${yScale(d.durationMs)})`)
          .attr('fill', color)
          .attr('fill-opacity', 0.7)
          .attr('stroke', color)
          .attr('cursor', 'pointer')
          .on('click', (_event, d) => onPointClick?.(d))
          .on('mouseenter', (event, d) => {
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
      } else {
        const diamond = d3.symbol().type(d3.symbolDiamond).size(50)
        group
          .append('path')
          .attr('d', diamond)
          .attr('transform', (d) => `translate(${xScale(d.captureTime)},${yScale(d.durationMs)})`)
          .attr('fill', color)
          .attr('fill-opacity', 0.7)
          .attr('stroke', color)
          .attr('cursor', 'pointer')
          .on('click', (_event, d) => onPointClick?.(d))
          .on('mouseenter', (event, d) => {
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
    }

    // Draw in order: normal (bottom), error, partial (top)
    drawPoints(normalPoints, NORMAL_COLOR, 'circle')
    drawPoints(errorPoints, ERROR_COLOR, 'triangle')
    drawPoints(partialPoints, PARTIAL_COLOR, 'diamond')

    // Brush
    const brush = d3
      .brushX()
      .extent([
        [0, 0],
        [width, chartHeight],
      ])
      .on('end', (event: d3.D3BrushEvent<unknown>) => {
        if (!event.selection) return
        const [x0, x1] = event.selection as [number, number]
        g.select<SVGGElement>('.brush').call(brush.move, null)
        onSelection?.(xScale.invert(x0).getTime(), xScale.invert(x1).getTime())
      })

    g.append('g').attr('class', 'brush').call(brush)

    // Ctrl+scroll zoom
    g.append('rect')
      .attr('width', width)
      .attr('height', chartHeight)
      .attr('fill', 'none')
      .attr('pointer-events', 'all')
      .style('cursor', 'crosshair')
      .on('wheel', function (event: WheelEvent) {
        if (!event.ctrlKey) return
        event.preventDefault()
        const zoomingOut = event.deltaY > 0
        const [mx] = d3.pointer(event)
        const xTime = xScale.invert(mx).getTime()
        const factor = zoomingOut ? 2 : 0.5
        onZoom?.(
          xTime - (xTime - chartFrom) * factor,
          xTime + (chartTo - xTime) * factor,
          zoomingOut
        )
      })
  }, [
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
