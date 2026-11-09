# Journal terminals and amend outcomes

A `Terminal` record (template 2 of the ME journal schema, `match-common/src/main/resources/sbe/me-journal-schema.xml`, schema id 4) authorizes settlement consumers to release the remaining hold for an order. The wire status `REJECTED` alone is not sufficient evidence for that: an update command can be rejected while the order it references stays live in the book.

The engine distinguishes these cases at the command handler:

| Outcome | Wire response | Journal terminal |
| --- | --- | --- |
| Amend validation or post-only crossing rejects before cancellation | REJECTED on old leg | No |
| Amend cannot find the order on the requested side | REJECTED on referenced leg | No |
| Accepted replace cancels old leg and creates replacement | CANCELLED on old leg, then replacement status | No for old leg |
| Replacement cannot rest after old leg was cancelled | REJECTED on new leg | Yes |
| Actual fill or explicit cancellation | FILLED or CANCELLED | Yes |
| New order is rejected | REJECTED | Yes |

An amend that ends in `ORDER_NOT_FOUND` does not prove closure: it may name the wrong side, and it did not itself close an order. Any earlier real closure has its own journal record.

`AmendSettlementJournalTest` exercises the real engine, publisher and journal. It checks that an order surviving a rejected amend can later fill, with the trade recorded before its release terminal, and that the wire rejection and the real terminal records are both present.

The legacy settlement journal schema (`settlement-journal-schema.xml`, schema id 3) is kept unchanged for older readers. Producers before this rule may have recorded pre-cancel amend rejections as terminals in that history; such history must be reconciled against the command log and authoritative order and ledger state before any financial replay. A history projector must never replay financial releases.
