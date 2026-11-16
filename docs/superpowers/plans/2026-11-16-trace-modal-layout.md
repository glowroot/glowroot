# Plan: Trace modal layout

## Files

- `ui/react-app/src/components/trace/TraceModal.tsx` — layout / chrome only
- `docs/changes/react-trace-modal-layout.md` — PR change note

## Tasks

1. Summary zone: headline + download; bold status lines; AttrRow label/value
2. Breakdown zone: tighten spacing; keep existing timer/JVM components
3. Detail toggles: shared link-style button; keep multi-open + lazy fetch
4. Build react-app, install glowroot-ui, restart sandbox `:4001/modern/`
