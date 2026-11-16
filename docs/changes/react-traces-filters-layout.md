# What

React `/modern` Transaction → Traces filter form uses classic label +
input-group layout (comparator prepended, milliseconds addon).

# Why

The previous stacked/wrap row made Response time and text filters hard to
scan (comparator, value, and units split awkwardly).

# How

`TransactionTracesPage.tsx` uses `GtFormGroup` rows and compact input-groups;
filter apply/clear/URL sync unchanged.

# Verification

Manual: sandbox `:4001/modern/` → Transaction → Traces → Filters.
