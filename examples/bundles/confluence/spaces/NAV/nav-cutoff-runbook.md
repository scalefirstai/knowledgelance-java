---
type: Runbook
title: NAV cut-off runbook
description: Daily procedure for the 16:00 CET dealing cut-off, late trades and the NAV strike.
tags: [nav, cut-off, runbook, operations]
resource: https://confluence.example.com/pages/viewpage.action?pageId=884211
source_id: "884211"
updated: 2026-10-20T08:00:00Z
space: NAV
acl: [fund-ops, nav-ops]
view_count: 1832
---
# Runbook

This runbook covers the daily dealing cut-off and the NAV strike for all UCITS funds.

## Cut-off

The dealing cut-off is 16:00 CET on each valuation day. Subscription and redemption orders
received after the cut-off are processed at the next valuation point. The cut-off is evaluated
by the fund accounting platform, never by the order management system (see FUND-1423).

### Late trades

A late trade is any portfolio trade booked after 16:00 CET with trade date T.

1. The platform routes the trade to the late-trade exception queue.
2. The NAV operations analyst checks whether the trade was executed before cut-off and booked
   late (allowed, with evidence) or executed after cut-off (roll to T+1).
3. Any late trade above 0.1% of fund NAV needs sign-off from the head of fund accounting.
4. Record the outcome in the exception log before the NAV strike.

### Exceptions approved by the valuation committee

| Situation | Treatment | Approver |
|---|---|---|
| Executed before cut-off, booked late | Include in T | NAV ops lead |
| Executed after cut-off | Roll to T+1 | Automatic |
| Market closure or system outage | Case by case | Valuation committee |

## NAV strike

The NAV is struck at 18:30 CET after price, FX and accrual checks pass. FX rates come from the
WM/Reuters 16:00 London fix; see [FX rate sourcing](fx-rates-sourcing.md).

## Escalation

Escalate to the head of fund accounting if the strike will miss 19:30 CET. Background on the
current late-trade policy is in the [decision record](late-trades-decision.md).
