/*
 * FlameGraph — D3 flame graph component using d3-flame-graph from npm.
 *
 * Replaces the custom-forked bower version from flame-graph.js.
 */

import { useEffect, useRef } from 'react'
import flamegraph from 'd3-flame-graph'
import * as d3 from 'd3'

export interface FlameGraphNode {
  name: string
  value: number
  children?: FlameGraphNode[]
}

export interface FlameGraphProps {
  /** Root nodes from the backend */
  rootNodes: FlameGraphNode[]
  /** Height per level in pixels (default: 18) */
  levelHeight?: number
  /** Total number of levels (from backend) */
  height?: number
  /** Total sample count from backend (used when wrapping multiple roots) */
  totalSampleCount?: number
  /** Include/exclude filter */
  filter?: string
  /** Truncate branches below this percentage (0-100, default: 0) */
  truncateBranchPercentage?: number
}

export function FlameGraph({
  rootNodes,
  levelHeight = 18,
  height,
  totalSampleCount,
  filter,
  truncateBranchPercentage = 0,
}: FlameGraphProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const chartRef = useRef<ReturnType<typeof flamegraph> | null>(null)

  useEffect(() => {
    const el = containerRef.current
    if (!el || rootNodes.length === 0) return

    // Build data — if single root node, use it directly; otherwise wrap in a virtual root
    let chartData: FlameGraphNode
    if (rootNodes.length === 1) {
      chartData = rootNodes[0]
    } else {
      const totalValue =
        totalSampleCount ?? rootNodes.reduce((sum, n) => sum + n.value, 0)
      chartData = {
        name: '<multiple root nodes>',
        value: totalValue,
        children: rootNodes,
      }
    }

    // Apply truncation filter if specified
    if (truncateBranchPercentage > 0) {
      const threshold = (chartData.value * truncateBranchPercentage) / 100
      chartData = filterTree(chartData, threshold)
    }

    // Clear previous
    d3.select(el).selectAll('*').remove()

    const containerWidth = el.clientWidth || 960

    const chart = flamegraph()
      .width(containerWidth)
      .cellHeight(levelHeight)
      .minFrameSize(1)

    chartRef.current = chart

    d3.select(el).datum(chartData).call(chart)

    // Resize handler
    const observer = new ResizeObserver(() => {
      const newWidth = el.clientWidth
      if (newWidth > 0 && chart) {
        chart.width(newWidth)
        d3.select(el).datum(chartData).call(chart)
      }
    })
    observer.observe(el)

    return () => {
      observer.disconnect()
      chartRef.current = null
      // Clean up d3-flame-graph tooltip
      d3.selectAll('.d3-flame-graph-tip').remove()
    }
  }, [rootNodes, height, levelHeight, totalSampleCount, filter, truncateBranchPercentage])

  if (rootNodes.length === 0) {
    return (
      <div className="px-4 py-3 text-sm text-gray-500">
        No flame graph data available.
      </div>
    )
  }

  return <div ref={containerRef} className="w-full overflow-x-auto" />
}

function filterTree(node: FlameGraphNode, threshold: number): FlameGraphNode {
  if (!node.children) return node
  const filtered = node.children
    .filter((child) => child.value >= threshold)
    .map((child) => filterTree(child, threshold))
  return { ...node, children: filtered }
}
