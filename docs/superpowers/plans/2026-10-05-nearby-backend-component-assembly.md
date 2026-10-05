# Assemble one typed Nearby backend component set

Build nonnullable endpoint dependencies from one borrowed datasource, one mandatory SFU lease
client with connected-participant support, one credential issuer and one actual webhook
verifier. Share typed JdbcVoiceAccess and JdbcSessionRepository across channels, direct calls,
sharing and packets. Resolve canonical CHANNEL/DIRECT_CALL room through actual SQL access in
a bounded transaction that ends before any SFU request; never authorize calls as channels.

Assemble community, removal, channel commands, invitations, session, roster, calls, consent,
packet and durable webhook inbox adapters. Expose bounded session/webhook reconciliation work
for the next owned application worker. Borrow the database/SFU/issuer; construction issues no
network and no SQL. No nullable production endpoint placeholders inside this component set.

Actual PG tests exercise the assembled APIs: public community/join + two voice sessions +
coordinate sharing/audience + disconnect preserves membership; membership alone cannot earn
session/sharing or audience; accepted friends call uses typed original claim and cannot share
channel coordinates; lease expiry reconciliation retires exact original claim. Tests use
measured issued SFU/credential effects and real SQL, not counts of constructor fields.

This stage supplies the tested assembly to the following AppDependencies/config/worker
activation task. It does not activate a partially configured rollout, deploy or claim native
audibility. Fresh exact PG, backend and Android suites before commit.
