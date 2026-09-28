# Ingress decode and application failures

Malformed wire input and a failed application callback have different recovery
requirements. A publisher can throw after matching has already consumed an order;
treating that exception as a dropped command would certify partially applied state.

Before dispatch, the demultiplexer checks physical buffer bounds, the declared
frame length, known fixed message prefixes and EngineConfig group bounds. These
checks remain active when Agrona's optional bounds checking is disabled. Decoders
also receive a view limited to the frame, so stale bytes in the backing receive
buffer cannot become an order. Invalid schema versions, malformed frames and
invalid enum bytes are counted and rejected before application.

After a decoded command enters an engine or application callback, any unexpected
RuntimeException or Error is an application fault. This includes exceptions such
as IllegalArgumentException and IndexOutOfBoundsException: their class alone does
not make an already-mutated command malformed input. The demultiplexer latches the
failure and propagates it. The service immediately closes readiness and invokes
its existing hookless process stop (exit code 1), with the command log position
and failure class logged before stopping. It does not wait for repeated failures.

If the process-stop hook returns (as in tests), the failed instance refuses all
further application callbacks, including orders, timers, role changes and
snapshots. Termination cleanup remains permitted. Background work cannot publish
a partially applied log position as complete or reopen readiness.

This is a failure boundary, not an in-memory rollback or automatic command retry.
Recovery requires a fresh process restoring verified state and replaying the
retained command log. A repeatable application defect can fail again on replay;
supervisor restart budgets and operator recovery policy still need separate
qualification. Never delete the failing log or save the partially applied state
as a recovery snapshot.

Tests cover real book mutation and trade-publication failures, poison input,
truncated frames over stale buffers, nonzero offsets, callback classification,
readiness/snapshot fencing, and the actual process exit without shutdown hooks.
Durable command identity/outcome, journal compatibility, and multi-node recovery
acceptance remain separate prerequisites for an OMS recovery cutover.
