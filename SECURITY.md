# Security Policy

## Reporting a vulnerability

Use [GitHub private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing/privately-reporting-a-vulnerability) on this repository.

Do **not** open a public issue for a real vulnerability (RCE, auth bypass, data exposure, etc.).

In the report, include:

- Glowroot version (and whether you run **embedded** or **central**)
- What you observed and how to reproduce it
- Impact (who can exploit it, what they get)

We triage against the latest release. Older releases are case-by-case.

## Supported versions

Security fixes are considered for the **latest release** published on [GitHub Releases](https://github.com/glowroot/glowroot/releases). We do not commit to patching older branches.

## Known security posture

These come up often. They are documented product/ops facts, not surprise CVEs:

| Topic | Reality | What to do |
| --- | --- | --- |
| Anonymous UI access | On a fresh install, Glowroot can expose an anonymous administrator until you configure users. | Create a real admin user, remove anonymous access, and do not expose the UI to the public internet. See [#1074](https://github.com/glowroot/glowroot/issues/1074). |
| Agent → Central auth | There is no built-in authentication between agents and the central collector. Knowing `collector.address` is enough to send data. | Keep gRPC off the public internet; use network ACL, VPN, reverse proxy allowlists, and HTTPS where configured. Native agent auth is tracked as [#311](https://github.com/glowroot/glowroot/issues/311). |
| Remote JMX `authenticate=false` | Glowroot does not require or set those JVM flags. | Remove remote JMX flags if you do not need remote JMX. See [#745](https://github.com/glowroot/glowroot/issues/745). |
| Scanner hits on shaded JARs | The agent relocates dependencies under `org.glowroot.agent.shaded.*`. Metadata-based scanners often flag “hidden” or old embedded libs without proving exploitability in Glowroot’s use. | Report with a concrete exploit path in Glowroot’s deployment model; version bumps alone are not a security advisory. See [#1221](https://github.com/glowroot/glowroot/issues/1221). |

## Out of scope for security reports

Please use a normal issue (or discussion) instead when the report is about:

- Operator misconfiguration (anonymous left enabled, UI/gRPC bound to a public address, optional remote JMX left open)
- Dependency scanner output with no Glowroot-specific reproduction or impact
- Feature requests such as agent↔Central authentication ([#311](https://github.com/glowroot/glowroot/issues/311))
