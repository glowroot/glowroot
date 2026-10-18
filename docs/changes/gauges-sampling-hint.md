# Gauges sampling hint (UI)

## What

JVM → Gauges shows a short italic note that values are sampled every 5 seconds
by default, and the chart help popover documents sampling limits plus the
unsupported test-only `-Dglowroot.internal.gaugeCollectionIntervalMillis` knob.

## Why

Operators sometimes expect the heap gauge chart to show brief allocation
spikes, or believe a heap dump “refreshes” history ([#1059](https://github.com/glowroot/glowroot/issues/1059)).
The chart already reflects periodic MBean samples; this makes that limit visible
in the UI.

## How

- `ui/app/views/jvm/gauge-values.html` — italic note above the chart; help
  button uses `gauge-chart.html` with `popover-class="gt-gauge-chart-popover"`
- `ui/app/template/help/gauge-chart.html` — navigation tips plus sampling notes
- `ui/app/styles/common.scss` — `.gt-gauge-chart-popover` max-width 520px so the
  long JVM arg fits without clipping

## Choices

Docs/UI hint only (no denser default sampling, no min/max rollup). Keeps
overhead and product surface unchanged while clarifying expected behavior.
Italic note instead of an alert box so the chart page stays quiet.

## Verification

- Embedded UI sandbox: JVM → Gauges shows the italic note; `?` popover shows
  sampling text and wraps the JVM arg inside a ~520px-wide popover.
