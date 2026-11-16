# What

React `/modern` transaction trace scatter uses circles for normal, error, and
active/partial points (classic Flot parity).

# Why

Triangle and diamond markers were noisier than the classic UI and harder to
read at a glance.

# How

`ui/react-app/src/components/chart/TracePointChart.tsx` draws all series as SVG
circles with the existing classic colors; legend icons match. e2e selectors
updated for the new series class names.

# Choices

Shapes were dropped entirely rather than polished — match classic behavior.

# Verification

Manual: Transaction → Traces scatter on embedded sandbox `:4001/modern/`.
e2e locator updated in `phase-c-parity.spec.ts`.

# Follow-ups

Separate PR: Trace modal layout rework.
