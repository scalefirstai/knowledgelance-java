---
type: Page
title: FX rate sourcing for NAV calculation
description: Which FX fixes are used for valuation, when snapshots are taken, and fallbacks.
tags: [fx, pricing, nav]
resource: https://confluence.example.com/pages/viewpage.action?pageId=880017
updated: 2026-09-02
space: NAV
acl: [fund-ops, nav-ops, pricing]
---
## Primary source

Multi-currency funds are valued with the WM/Reuters 16:00 London closing spot fix. The snapshot
is captured when the fix is published, normally at 16:02 London time.

## Fallback

If the fix is not published by 16:30, use the Bloomberg BFIX 16:00 rate and record the fallback
in the pricing exception log. Known issue: FUND-1388.
