# glowroot-central Helm chart

Deploys [Glowroot Central](https://github.com/glowroot/glowroot) using the official
`glowroot/glowroot-central` image.

Cassandra is **not** bundled. Provide contact points (and a 32-character hex
`symmetricEncryptionKey`) via values or an existing Secret.

Agent install / Service DNS: [Kubernetes wiki](https://github.com/glowroot/glowroot/wiki/Kubernetes).

Community predecessor (admin-heavy, patched image):
[novumrgi/helm glowroot chart](https://github.com/novumrgi/helm/tree/master/charts/glowroot).

## Install

```bash
helm install glowroot ./helm/glowroot-central \
  --set cassandra.contactPoints=cassandra.default.svc \
  --set cassandra.symmetricEncryptionKey=0123456789abcdef0123456789abcdef
```

## Agent

```properties
collector.address=http://glowroot-glowroot-central-collector:8181
agent.id=MyApp::pod-a
```

Use the release’s `*-collector` Service name and port **8181**, not UI **4000**.

## admin-default.json

Set `adminDefault.enabled=true` and `adminDefault.json` to a JSON document.
Applied only on **first** Central bootstrap (empty `central_config`).

LDAP/SMTP may use plain `"password"`; Central converts to `encryptedPassword`
(requires `cassandra.symmetricEncryptionKey`). See issue #729.

## Values

| Key | Description | Default |
| --- | --- | --- |
| `image.repository` | Central image | `glowroot/glowroot-central` |
| `image.tag` | Image tag | `0.14.7` |
| `cassandra.contactPoints` | Cassandra hosts | `""` |
| `cassandra.symmetricEncryptionKey` | 32-char hex | `""` |
| `cassandra.existingSecret` | Secret with Cassandra keys | `""` |
| `service.ui.port` | UI Service port | `4000` |
| `service.collector.port` | gRPC Service port | `8181` |
| `adminDefault.enabled` | Mount admin-default.json | `false` |
| `ingress.enabled` | Create Ingress for UI | `false` |
