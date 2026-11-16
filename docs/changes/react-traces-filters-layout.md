# What

React `/modern` Transaction → Traces filters use underline-only selects/inputs
(no rectangular bordered boxes).

# Why

Boxed controls still felt heavy; keep classic field order with a light chrome.

# How

`TransactionTracesPage.tsx`: `SlimFilterRow` + `border-b` only controls;
apply/clear logic unchanged.

# Verification

Manual: sandbox `:4001/modern/` → Transaction → Traces → Filters.
