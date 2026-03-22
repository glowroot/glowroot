declare module 'd3-flame-graph' {
  import type { Selection } from 'd3-selection'

  interface FlameGraphChart {
    width(w: number): FlameGraphChart
    cellHeight(h: number): FlameGraphChart
    minFrameSize(s: number): FlameGraphChart
    (selection: Selection<any, any, any, any>): void
  }

  export default function flamegraph(): FlameGraphChart
  export function colorMapper(value: unknown): string
  export function tooltip(): unknown
}
