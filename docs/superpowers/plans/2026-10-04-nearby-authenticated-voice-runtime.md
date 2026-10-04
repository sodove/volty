# Authenticated voice runtime lifetime

Compose original account control, account-native permission and VoiceRuntime. Borrow
the repository/application parent context captured by SocialAuthenticatedAccount;
allocate one owned SupervisorJob under that root, never under the auth Job. Validate
canonical actor/root/queued dispatcher before adapters/children. Suspend control creation
captures original cleanup credentials. No UI/backend deployment or membership changes.

An undispatched guardian on the actual account Job synchronously fences local and HTTP
admission, then submits explicit runtime retirement in NonCancellable. Explicit retirement
also works from a cancelled caller. Root-owned HTTP/native stop retries keep their original
tags and bearer. Public state keeps local release, failed close and remote stop projections
separate. Retirement submission failure is visible, not promoted to resource cleanup ACK.

Mandatory supplied global native admission predicate has no permit-all default in this
composition: new join/sharing and engine driver require it plus actual account permission.
The application coordinator must supply it from older resource cleanup evidence. This
stage does not implement that global coordinator or claim that two account owners can
safely run simultaneously. Positive commands validate canonical intent before mailbox;
disconnect/hide/retire preserve original cleanup and never remove membership.

No automatic job/HTTP close or convenience dispose: a future drain coordinator must
account for held network/native producers and independent stop ACKs before disposal.
Original parent cancellation/process death is not physical release evidence. Foreground,
durable recovery and Android factory binding remain required separate integration.

Tests first with actual auth + HTTP and fake issued SDK: logout while real auth child
is held, failed local close and remote pending independently retry after auth Job dies;
cancelled caller disconnect preserves login; mandatory gate denies HTTP/allocation until
allowed; invalid dispatcher allocates no root children. Finite pulse Channel, original
token/path observations, held gates released finally. RED then fresh exact full suite,
parent manual review after earlier Luna quota.
