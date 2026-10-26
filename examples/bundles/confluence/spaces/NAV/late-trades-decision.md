---
type: Decision
title: "Decision: late trades are evaluated in CET by the fund accounting platform"
description: Following FUND-1423, the cut-off check moves from the OMS to the fund accounting platform and uses CET.
tags: [decision, nav, cut-off]
resource: https://confluence.example.com/pages/viewpage.action?pageId=884390
source_id: "884390"
updated: 2026-10-19
space: NAV
acl: [fund-ops, nav-ops]
---
## Context

Incident FUND-1423 showed that the OMS evaluated the dealing cut-off in the desk's local time.

## Decision

The fund accounting platform is the single place where the cut-off is evaluated, always in CET.
Late trades go to an exception queue described in the [NAV cut-off runbook](nav-cutoff-runbook.md).

## Consequences

Restatements for the affected funds were approved by the valuation committee on 2026-10-17.
