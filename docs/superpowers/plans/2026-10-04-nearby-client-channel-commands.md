# Client channel commands implementation plan

Build the client counterpart of the authenticated channel command API. Screen assembly
and production DI remain later; commands affect channels only, never permanent membership
or automatic voice/trip connection. Caller owns stable channel/operation IDs across retries.

1. Add meaningful MockEngine tests before API: exact HTTP method/path/body/bearer and stable
   payload; strict response identity/types/name/positive create revision/nondefault channel;
   delete cleanup flag independent of session shape; foreign parent/channel/op rejection;
   bounded body and strict UTF-8, redirects disabled, safe typed errors, finite timeout,
   cancellation without retry, invalid input before bearer/network.
2. Define domain ChannelCommandsPort with separate creation/deletion receipts and typed
   failures. Implement HTTPS-only ChannelCommandsHttpPort with nonempty host, no URL
   credentials/query/fragment, bounded response16KiB and whole-request10s timeout.
3. Narrow Luna/xhigh source review and fix loop; fresh full Android/backend suites, evidence,
   commit. No production activation or device claim.

Private invitation HTTP/client, authorized roster publication/sharing and Android runtime
integration follow the existing rewrite plan; UI plans/mockups remain near assembly.
