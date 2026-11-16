# What

React `/modern` Transaction → Traces filters use slim label rows with thin
select/input controls (no fused input-group boxes).

# Why

Bulky boxed controls were hard to scan; keep classic field order with a
lighter 2026 chrome.

# How

`TransactionTracesPage.tsx`: `SlimFilterRow` + thin `h-7` controls; apply/clear
logic unchanged.

# Verification

Manual: sandbox `:4001/modern/` → Transaction → Traces → Filters.
