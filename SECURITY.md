# Security Policy

Open Exchange is open-source exchange infrastructure. It is currently
**beta software**: it has not had an external security audit, and it is not
yet recommended for production deployments holding real funds.

## Scope

This project provides the exchange infrastructure layer: matching, order
management, risk checks, persistence, and operations tooling. Deployments are
expected to provide their own identity and access management, KYC/AML,
custody, and network perimeter. Vulnerabilities in those integration layers
belong to the integrating system; vulnerabilities in what this repository
ships belong here.

Reports we especially care about:

- Order, balance, or ledger integrity violations (theft, double-spend,
  conservation breaks)
- Authentication or authorization bypass in shipped endpoints
- Remote code execution, injection, or deserialization issues
- Denial of service that a single unprivileged client can trigger

Out of scope: issues that require the attacker to already control the box or
the deployment configuration (the dev profiles deliberately trade security
for convenience and say so in their docs).

## Supported versions

| Version | Supported |
| ------- | --------- |
| Latest tagged release (currently v0.3.0-beta) | Yes |
| `main` | Yes |
| Older tags | No |

## Reporting a vulnerability

Please report vulnerabilities privately. Do not open a public issue.

- Email: **emrebulutlar@gmail.com** (include the repo name, an impact
  summary, and reproduction steps)
- Or use GitHub's private reporting if available on this repository
  (Security tab, "Report a vulnerability")

You will get an acknowledgement within a few days. This is a small
open-source project, so triage and fixes are best effort, but integrity
issues in the matching or ledger path will be treated as top priority.
Please give us a reasonable window to fix before public disclosure; we will
credit reporters in the release notes unless you prefer otherwise.
