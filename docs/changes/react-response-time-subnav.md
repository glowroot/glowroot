# What

Modern UI Response time pages (Average / Percentiles / Throughput) share a segmented sub-nav. Percentiles customize moved next to the chart legend (pencil), matching classic placement.

# Why

On Percentiles, the selected view, live percentile list, and customize control shared one row. Selection was hard to see; customize looked like part of the tab switcher.

# How

- Added `ResponseTimeSubNav` used by the three response-time pages.
- Added optional `legendTrailing` on `TimeSeriesChart` for the percentiles edit control.
- Left summary percentile values under the chart; customize panel opens above the chart when editing.

# Choices

Segmented control instead of blue text links for clearer active state. Kept customize near the legend (classic) rather than in the sub-nav. No backend/API changes.

# Verification

- `npx tsc --noEmit` in `ui/react-app`
- Manual check with Vite (`npm run dev`) + embedded UI sandbox on `:4000`: tab selection, customize pencil, Apply/Cancel
