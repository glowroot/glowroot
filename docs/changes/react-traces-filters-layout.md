# What

React `/modern` Transaction → Traces filters use underline-only selects/inputs
(no rectangular bordered boxes).

# Why

Boxed controls still felt heavy; keep classic field order with a light chrome.

# How

`TransactionTracesPage.tsx`: `SlimFilterRow` + `border-b` inputs; selects use
`appearance-none` + light SVG chevron (no OS box); apply/clear unchanged.

# Verification

Manual: sandbox `:4001/modern/` → Transaction → Traces → Filters.
