# Settlement journal and amend outcomes

`JournalTerminal` authorizes settlement consumers to release the remaining hold
for an OMS order. The wire status `REJECTED` alone is not sufficient evidence for
that action: an update command can be rejected while its referenced order stays
live in the book.

The engine therefore distinguishes these cases at the command handler:

| Outcome | OMS wire response | Settlement terminal |
| --- | --- | --- |
| Amend validation or post-only crossing rejects before cancellation | REJECTED on old leg | No |
| Amend cannot find the order on the requested side | REJECTED on referenced leg | No |
| Accepted replace cancels old leg and creates replacement | CANCELLED on old leg, then replacement status | No for old leg |
| Replacement cannot rest after old leg was cancelled | REJECTED on new leg | Yes |
| Actual fill or explicit cancellation | FILLED or CANCELLED | Yes |
| New order is rejected | REJECTED | Yes |

An amend with `ORDER_NOT_FOUND` does not prove closure: it may name the wrong
side, and it did not itself close an order. Any earlier actual closure retains
its own journal event. The existing wire schema and OMS leg routing are unchanged.

`AmendSettlementJournalTest` exercises the real engine, publisher and journal.
It verifies that an order surviving a rejected amend can later fill, with the
trade recorded before its release terminal. It also verifies the wire rejection
and that real terminal events are retained.

This contract does not make the settlement journal a complete order-command
recovery log. Rejected command outcomes and replacement lineage need a separate,
versioned durable contract before they can authoritatively rebuild OMS workflows.
Synthetic parent orders and financial release ownership also require their own
acceptance coverage.

Older producers may already have recorded pre-cancel amend rejections as
terminals. This source fix changes future emission; it neither rewrites Archive
history nor compensates past releases. Such history must be reconciled with the
command log and authoritative order/ledger state before financial replay or
order-recovery cutover. A history projector must never replay financial releases.
