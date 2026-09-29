# Durable command protocol (candidate, not activated)

Order schema 1/v11 adds ingress template 9 and journal template 28. The 128-bit
command identity is bound to every payload field. Retry returns the original
application result and emits a fresh journal delivery, without replaying trades,
financial terminals, order placement or amendment. Conflicting payloads cannot
replace the canonical result. Separate commands may share an OMS parent (slices).

The result records the original log position/time, command kind, parent, old leg,
new/result leg, old-leg cancellation flag, order status and reject reason. Result
codes: 0 applied, 1 engine rejected, 2 identity conflict, 3 ledger capacity,
4 unknown/non-durable/closed leg, 5 owner/market/side mismatch, 6 unknown market.
Unknown leg is not proof of cancellation and emits no financial terminal.

Whether a node journals (`SETTLEMENT_JOURNAL_ENABLED`) is node configuration, not
replicated state, so it never changes what a command does: a journal-dark node applies
and deduplicates exactly like a journaled one and only counts the outcome it could not
write (`match_dark_command_outcomes_total`). The OMS resolves a command only from a
projected outcome, so a dark journal leaves the command unresolved rather than halting
the cluster. Where the journal is on, its append is synchronous with application, and an
append/apply failure uses the service's existing immediate halt fence; no dirty state may
be snapshotted. Outcomes are
independent from settlement trade/terminal schema 3/v1, which remains unchanged.

Ledger snapshots carry a versioned envelope with total length and CRC32C. A
truncated/corrupt suffix cannot silently become a legacy snapshot without dedupe.
Legacy snapshots remain readable and ledger-free serialization remains unchanged.
Once durable commands have entered the log, downgrade to older readers is unsafe.
All replicas and journal consumers must understand this protocol before activation.

The initial ledger admits at most 100,000 identities and never silently evicts.
At capacity new commands receive an explicit result 3 without book mutation;
existing retries still work. This is a safety bound, not production retention.
A replicated retention/epoch protocol and corresponding load/soak qualification
are required before enabling this producer. Live legacy legs cannot be amended
or cancelled by the durable lane until their ownership is migrated authoritatively.
OMS production admission is not switched by this protocol change.
