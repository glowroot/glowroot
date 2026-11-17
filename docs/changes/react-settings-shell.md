# What

Modern Configuration and Administration pages share a settings shell (`SettingsPage` / `SettingsSection`): less card chrome, flatter inputs, aligned headers, and labeled sidebar groups. Item order matches classic UI.

# Why

Outer bordered cards, duplicate page titles, heavy shadowed inputs, and inconsistent Admin vs Config headers made long settings forms hard to scan and wasteful of space.

# How

- Added `SettingsPage` and `SettingsSection` under `ui/react-app/src/components/shared/`.
- Tightened `GtFormGroup` (fixed label column, `max-w-[40em]` controls).
- Flattened shared `Input` / `Select` / `Textarea` (no shadow, thinner focus ring, `h-8`).
- Aligned `AdminLayout` header with Config; sidebar group captions (Access / Server / Delivery, Capture / Ops / Instrumentation).
- Migrated all `routes/config/*` and `routes/admin/*` pages off outer `rounded-lg border` cards (Storage and LDAP use sections).
- Detail pages use a chevron back control on `SettingsPage` (parent list name) instead of “Return to list”; titles are the item name (`New`, not `Alerts — <New>`).
- Config sidebar: classic order in separate boxes (no Capture/Ops captions); Admin keeps Access / Server / Delivery captions.
- Advanced: short per-field help + footer wiki link (`Advanced-and-config.json`), same pattern as Storage.
- Instrumentation New/edit: short help + footer wiki link (`Instrumentation`).

# Choices

Modern-but-dense (2B): typography and hairline sections instead of classic Bootstrap cards; kept classic sidebar order for muscle memory. Global flatter controls — primary consumers are settings; other modern pages stay compatible. Dropped config section captions after they read noisier than helpful.

# Verification

- `npx tsc --noEmit` in `ui/react-app`
- Manual Vite + sandbox: Config General / list pages; Admin General / Storage / Users / LDAP sections
