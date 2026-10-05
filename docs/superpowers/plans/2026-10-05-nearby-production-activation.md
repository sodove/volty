# Nearby production activation

Continue the approved voice-spaces rewrite without deploying it. Preserve accounts and friends; do not import legacy groups.

1. Add explicit legacy/voice_spaces configuration, validate environment and directly constructed configs before opening the database. Never infer singleton ownership from SFU readiness.
2. Install the complete typed component graph and maintenance port through the real application factory. Reject partial production activation; close the owned pool if startup fails.
3. Before publishing voice_spaces dependencies, transactionally withdraw old sharing consent and ephemeral payloads. Preserve accounts, friendships and historical groups. Test repetition and rollback on disposable PostgreSQL.
4. Verify actual factory wiring, complete fresh backend/Android suites and PostgreSQL changes before committing. No stock-SFU fallback, deploy, native-audio or independent-review claims.

Production rollout still requires the protocol-4 patched SFU and coordinated retirement of the old media process. A configuration flag alone is not proof that old media has stopped.
