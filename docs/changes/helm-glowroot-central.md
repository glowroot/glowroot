# Official glowroot-central Helm chart + admin-default password encrypt

## What

Adds `helm/glowroot-central` for Central only, and fixes `AllAdminConfigUtil`
so plain LDAP/SMTP passwords in `admin-default.json` are encrypted on apply
(`remove(password)`).

## Why

Operators ask for an upstream Helm chart (#1240 / Discussion #973). Separately,
#729 left plaintext passwords in stored admin config / UI when seeded from
admin-default.

## How

- Chart: stock `glowroot/glowroot-central` image; UI `:4000` + gRPC `:8181`;
  external Cassandra via values/Secret; optional admin-default mount; Ingress
- `AllAdminConfigUtil.updateEncryptedPassword` uses `remove(password)`

## Choices

No Bitnami subchart / no forked image. Does **not** add `helmMode` re-apply
every restart (#733 stays open for that). Embedded users can ignore.

## Verification

- `helm template` fails without contact points / encryption key (unless
  `existingSecret`)
- Real cluster: UI :4000, agents at `*-collector:8181`
