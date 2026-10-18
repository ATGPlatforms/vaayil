# Automation intents (Tasker, adb)

Vaayil exposes a public broadcast receiver for automation apps (Tasker,
MacroDroid, Automate) and for `adb shell` scripting. Three actions, all
received by the receiver `dev.vaayil.core.automation.AutomationReceiver`
(declared in the `:core:automation` library manifest). The receiver ships
**disabled**; the Settings toggle enables it and shows the token every
broadcast must carry.

## Contract

External control is **off by default**. Enable it in Settings → Automation → "Allow
external control": Vaayil enables the receiver component and shows a per-install token.
Every broadcast must carry the token in the `token` extra; broadcasts without it, or with
a wrong one, are ignored. "New token" in Settings invalidates the old one — Settings
asks for confirmation first, since every automation using the old token stops working.

```
Actions (explicit-component broadcast to
dev.vaayil.android/dev.vaayil.core.automation.AutomationReceiver):
  dev.vaayil.automation.CONNECT    extras: token (required), profile (optional: exact id or exact name)
  dev.vaayil.automation.DISCONNECT extras: token (required)
  dev.vaayil.automation.TOGGLE     extras: token (required), profile (optional)
Profile resolution for CONNECT: with `profile` → exact id, then exact name, else NO-OP;
without `profile` → the stored active profile, else the last connected profile (each only if
that profile still exists), else the most recently updated profile, else no-op.
```

adb example:

```
adb shell am broadcast -n dev.vaayil.android/dev.vaayil.core.automation.AutomationReceiver \
  -a dev.vaayil.automation.CONNECT --es token '<TOKEN FROM SETTINGS>' --es profile 'Home'
```

## Why the gate

Any installed app can send an explicit broadcast. Before 2026-09-10 one intent from any app
dropped the tunnel and the only sign was the notification disappearing. The toggle keeps the
component disabled until the user opts in, and the token keeps unrelated apps out once they
do. The token is a local secret, not a key: it grants connect/disconnect control only.

## Background starts and VPN permission

Android 12+ limits foreground-service starts from the background, but a VPN app
the user has consented to is exempt (`REASON_OP_ACTIVATE_VPN` in AOSP
`ActiveServices`). So once Vaayil holds VPN permission, CONNECT, DISCONNECT and
TOGGLE work while Vaayil is fully in the background, with no extra setup. You
don't need to change Vaayil's battery optimization for them.

Vaayil holds VPN permission after you connect once from the app and accept
Android's VPN request. It loses it when you choose "Forget VPN" for Vaayil in
Android's VPN settings.

Without that permission, a CONNECT starts nothing. Instead Vaayil posts one
notification, "Vaayil couldn't connect", with the text "VPN permission is off.
Open Vaayil and connect once to allow it." Tap it to open Vaayil, then connect
once and accept Android's request; later broadcasts then work. The same
notification comes from the Wi-Fi guard, the mobile-data rule and connect on
boot, for the same reason. The next tunnel notification replaces it.

A DISCONNECT while no tunnel is up does nothing.

## Delivery: explicit component required

The receiver ships **without an intent-filter by design** (it answers explicit
component broadcasts only — this keeps the surface free of implicit-action
matching). It also ships **disabled**: until "Allow external control" is on,
the package manager drops every broadcast to it. Callers **MUST target the
component explicitly**; an action-only (implicit) broadcast is **not
delivered** — implicit broadcasts resolve against intent-filters, and there is
none.

The component is `<applicationId>/dev.vaayil.core.automation.AutomationReceiver`,
i.e. `dev.vaayil.android/dev.vaayil.core.automation.AutomationReceiver`.

Every transition goes through `VpnTunnelService` intents — the same seam the
app UI uses — so automation cannot bypass anything the UI cannot: the tunnel
notification always reflects the resulting state, and the tunnel stays
foregrounded for its whole life.

## adb examples

`adb shell` writes each command line — token included — to the device log (`logcat`).
Use adb only for testing, and choose **New token** in Settings afterwards if that log
may be shared.

```bash
RX=dev.vaayil.android/dev.vaayil.core.automation.AutomationReceiver
TOKEN='<TOKEN FROM SETTINGS>'   # Settings → Automation → Allow external control

# Connect to a profile by name
adb shell am broadcast -a dev.vaayil.automation.CONNECT -n $RX --es token "$TOKEN" --es profile Home

# Connect to a profile by id
adb shell am broadcast -a dev.vaayil.automation.CONNECT -n $RX --es token "$TOKEN" --es profile p1

# Connect the stored active profile (or, after a disconnect, the last connected one)
adb shell am broadcast -a dev.vaayil.automation.CONNECT -n $RX --es token "$TOKEN"

# Disconnect
adb shell am broadcast -a dev.vaayil.automation.DISCONNECT -n $RX --es token "$TOKEN"

# Toggle (disconnect if connected/connecting, else connect the active or last connected profile)
adb shell am broadcast -a dev.vaayil.automation.TOGGLE -n $RX --es token "$TOKEN"
```

(`--es` sets a String extra; extra names are `token` and `profile`, case-sensitive.
`-n` sets the explicit target component — required, per the note above. A broadcast
without the token, or with a stale one, is silently ignored.)

## Tasker recipe

1. **Task → Action → Send Intent.**
2. **Action:** `dev.vaayil.automation.CONNECT`
3. **Extras:** `token:<TOKEN FROM SETTINGS>` (required), then `profile:Home`
   (optional — omit to use the stored active profile, else the last connected one)
4. **Type:** leave empty (text)
5. **Package:** `dev.vaayil.android`
6. **Class:** `dev.vaayil.core.automation.AutomationReceiver`
7. **Target:** **Broadcast Receiver**

With Package and Class set, Tasker addresses the receiver component
explicitly — which is **required**: the receiver has no intent-filter, so an
implicit, action-only Send Intent (Package/Class empty) is silently not
delivered. On top of that, the component ships disabled until "Allow external
control" is enabled, and every broadcast is dropped without the matching
`token` extra. Use `dev.vaayil.automation.DISCONNECT` or
`dev.vaayil.automation.TOGGLE` the same way (no `profile` extra needed).

## Security note

The receiver is exported, disabled by default, and token-gated. Any app can
*address* the component, but it takes the token — and the user's opt-in — to
drive the tunnel. The actions can only **connect or disconnect existing user
profiles**: there is no data-plane access and no config read/write, and every
transition is user-visible via the persistent tunnel notification.

The token is a local secret: Vaayil never logs it and never shows it outside Settings. Treat
profile names as public — an app that holds the token can address profiles by
name or id. Do not encode secrets in profile names.
