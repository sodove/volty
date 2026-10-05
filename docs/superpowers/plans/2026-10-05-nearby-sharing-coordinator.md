# Root channel sharing integration

An account/runtime-bound coordinator adopts explicit original-session capture owners. Enable is
root-owned and UI owns only its waiter. Actual typed voice ADD starts the selected capture only;
actual location foreground/consent/native readiness then creates its unique root publisher.
REMOVE/CLEAR synchronously snapshot and fence that original selection, clear packet/publication,
and release adopted capture via its original cleanup. Foreign/null origins do not touch another
selection. Failed or retired startup must hide the voice sharing flag only if the selection is
still current, never an old owner's callback against a newer selection.

Before adoption validate account/runtime/session and unique capture binding. Account retirement,
root close and explicit close fence positives; retained original captures expose/retry failed
native cleanup. Close result describes native cleanup only, not remote revoke acknowledgement.
Borrowed authentication/runtime/control/navigation survive sharing close. Tests use actual
voice reducer callbacks and HTTP: cancelled waiter/FGS pending, full capture-to-packet path,
hide during held consent, replacement, failure retry. No UI/native/device/product activation.
