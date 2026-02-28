---
name: update-grpc
description: Procedure to update gRPC, Netty, and BoringSSL versions in this Java/Maven project while maintaining compatibility
allowed-tools: Read, Grep, WebFetch, Edit
---

gRPC, Netty, and BoringSSL are tightly coupled together. Incompatible version combinations will cause runtime failures. When updating gRPC, you **must** follow this procedure:

1. Browse the compatibility matrix at https://github.com/grpc/grpc-java/blob/master/SECURITY.md#netty to determine the correct Netty and BoringSSL (netty-tcnative-boringssl-static) versions for the target gRPC version.

2. Update the version properties in **all** of the following POM files:
    - `pom.xml` (root)
    - `central/pom.xml`
    - `agent/shaded/core/pom.xml`
    - `agent/shaded/central-https-linux/pom.xml`
    - `agent/shaded/central-https-windows/pom.xml`
    - `agent/shaded/central-https-osx/pom.xml`

3. All three versions (gRPC, Netty, BoringSSL) must be updated **together** — never update one without checking the others.

4. After modifying the POM files, grep the project for any other references to the old versions to ensure nothing was missed:
```
   grep -r "OLD_GRPC_VERSION" --include="pom.xml"
   grep -r "OLD_NETTY_VERSION" --include="pom.xml"
   grep -r "OLD_BORINGSSL_VERSION" --include="pom.xml"
```