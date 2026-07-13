# Play Console Declarations (prepared for v1.1)

## Foreground service: specialUse

**Manifest:**

```xml
<service
    android:name=".service.ConnectionService"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="Maintains user-initiated interactive SSH terminal sessions, file transfers, and port forwards while the app is in the background" />
</service>
```

**Why specialUse:** `dataSync` carries a ~6-hour runtime cap on Android 15 (targetSdk
35+), which disqualifies it for a terminal session left attached overnight.
`connectedDevice` is the documented fallback if review pushes back (its description
covers network-connected devices), but "server" vs "device" is arguable — lead with
specialUse.

**Declaration text (Play Console):**

> Charon is an SSH client. When the user opens a terminal session, file transfer, or
> port forward, the connection must remain alive while they switch apps — an SSH TCP
> session cannot be handed off to a system API or rescheduled; disconnecting would
> terminate the user's remote programs. The foreground service runs only while the user
> has at least one active, user-initiated connection, shows a persistent notification
> with disconnect actions, and stops when the last session closes.

**Demo screencast script:** connect to a host → run `htop` → home button → wait →
return: session alive, htop still running. Show the notification and its disconnect
action.

## Sensitive permissions

- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — requested in-app with rationale only when the
  user enables "keep sessions alive aggressively"; core function works without it
- No location, no contacts, no SMS. Keys never leave the device except inside the
  user-initiated encrypted vault export.
