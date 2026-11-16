# What

Rework React `/modern` Trace modal layout into Summary / Breakdown / Detail
zones with classic-style attribute rows and link toggles.

# Why

The ported modal felt dense and disorganized (status pills, beige buttons,
flat stacking) compared with the classic Glowroot trace view.

# How

`TraceModal.tsx` chrome only: AttrRow + DetailToggle helpers; multi-open
Entries / Query stats / Profiles preserved with lazy fetch.

# Choices

Kept multi-open (classic) instead of accordion. Did not change entry-tree or
query-sort logic.

# Verification

Manual: embedded sandbox `:4001/modern/` → Traces → open a point → expand
multiple detail sections.
