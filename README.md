# Pixel Router Service (Pixel 7a / lynx)

Prototype system app for a dedicated Pixel 7a running a **custom Android ROM**.
It restores Ethernet or Wi-Fi tethering after boot, including before first
unlock, and forwards received text SMS to another phone and/or SMTP mailbox.
It is not an installable ordinary APK: Ethernet tethering uses a privileged
permission and a platform API. This repository has **not yet been compiled or
tested on a Pixel 7a**. Do not make it your only Internet connection yet.

## Modes

- **Home** (default): when an `ethN`/`enx...` interface appears, request
  Ethernet tethering. Connect the adapter to UniFi WAN2, set WAN2 to DHCP and
  configure UniFi failover separately.
- **Travel**: request Wi-Fi hotspot tethering. Configure the hotspot SSID,
  password and band in Android Settings before selecting this mode.
- **Auto**: Ethernet when the interface is present, otherwise Wi-Fi hotspot.
  Do not choose Auto for an unattended rack if a failed USB hub should leave
  the hotspot off.

The OS selects the upstream. Keep Wi-Fi client disconnected so that mobile
data is the upstream. A successful tethering callback does **not** prove that
the mobile data connection has Internet; verify from a downstream client.

## Integrating in a LineageOS 23.2 build for lynx

1. Set up a **private** LineageOS source tree following the official lynx
   build guide. Start from a test device or a recoverable installation.
2. Copy this entire directory to `packages/apps/PixelRouterService`.
3. Add `PRODUCT_PACKAGES += PixelRouterService` to your device product
   configuration (or to your own product makefile).
4. Place `permissions/privapp-permissions-pixelrouter.xml` in
   `system_ext/etc/permissions/` of the image. For example, in the product
   makefile, with the source at the path from step 2:

   ```make
   PRODUCT_COPY_FILES += \
       packages/apps/PixelRouterService/permissions/privapp-permissions-pixelrouter.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/privapp-permissions-pixelrouter.xml
   ```

5. Build your **user** image, sign release images with keys you control and
   keep verified boot enabled according to that ROM's release procedure.
   This module uses `certificate: "platform"`; do not distribute an APK
   signed with public test keys. Confirm the relevant LineageOS build's
   platform API and privileged permission requirements before production use.
6. After installation and first unlock, open **Pixel Router**, grant RECEIVE_SMS
   and SEND_SMS, save your settings, and configure Android hotspot settings.
   Runtime SMS permission grants persist across an ordinary reboot. Do not
   disable the screen lock. If a build restricts SMS permissions for this
   system app, grant them through the ROM's default-permission policy rather
   than bypassing permission enforcement.

The manifest requests `android:persistent` as a system-image app so Android
will restart its process. Whether that behavior and `startService` are accepted
before unlock must be measured on the final ROM. Avoid root, permissive SELinux,
and ADB as runtime dependencies. The service is not an Android `system_server`
module; a crash loop or ROM-specific background limit can still interrupt it.

## SMS and SMTP

The UI accepts a destination phone number, and an SMTP host, account/from
address, app password and recipient. SMTP is **implicit TLS on port 465** with
certificate and hostname validation plus `AUTH PLAIN` inside TLS. It does not
support OAuth, STARTTLS/587, multiple recipients, or arbitrary SMTP auth
mechanisms. Use a dedicated SMTP account supporting app passwords.

Message data, the queue and the SMTP credential are in Android's
device-protected encrypted storage because they must work before first unlock.
Anyone with access to the running system in Direct Boot can access these data
through the privileged app. There is no cloud relay in the project.

SMS submission is **at most once automatically**. The app stores `submitting`
before sending; if Android crashes before all send callbacks arrive, the status
remains uncertain and requires manual review. Automatic retries could send a
duplicate OTP. SMTP uses retry with an exponential delay and a stable
`Message-ID`; servers may still deliver duplicate e-mails if the connection
dies just after accepting DATA. Delivered items are retained for seven days.
The queue holds at most 250 items and logs an error if full. MMS and RCS are
not handled. Calls must be forwarded through the mobile operator separately.

## Recovery and acceptance tests

Use `adb logcat -s PixelRouter` only while commissioning. Disable ADB after
testing. The UI shows current state and queue counts; logs deliberately omit
message bodies and the SMTP password.

| Test | Acceptance condition |
| --- | --- |
| Cold reboot with Ethernet already plugged in; do not unlock | WAN2 gets DHCP and a LAN client reaches the Internet over mobile data. |
| Plug/unplug adapter with screen locked | Tethering starts within about a minute; profile Home does not activate Wi-Fi hotspot. |
| Reboot in Travel mode; do not unlock | Hotspot comes up; client obtains DHCP and Internet through SIM. |
| Receive a multipart SMS before first unlock | One forwarded SMS, one e-mail, sender retained; queue clears. |
| Disable mobile data/SMTP temporarily | E-mail remains queued and sends after connectivity returns. |
| Reboot while SMS status is `submitting` | App shows an uncertain SMS rather than sending a duplicate. |
| Turn off completely, drain battery, then restore power | Measure whether the actual phone powers on; software cannot solve a device that remains off. |
| Run for seven days on PD hub and UPS | No unplanned restart, heat issue, missed messages, or WAN2 loss. |

GrapheneOS USB-C restrictions, auto-reboot and SIM PIN are *device settings*;
they are not changed by this app. On a custom LineageOS build, inspect its own
USB and reboot policies. A SIM PIN and the phone's first-unlock behavior must
be verified separately. If the SIM asks for a PIN after reboot, the mobile
network cannot recover unattended until it is entered.

## Linux and Docker

Docker is deliberately outside the phone app. A later companion Linux host
can obtain 5G upstream via USB tethering and offer an Ethernet port to UniFi,
an access point for travel and a normal ARM64 Docker installation. This
requires a separate power and USB role test; it is not implemented here.
An Android-hosted Linux VM is an experimental future branch, not a dependency
of RouterService or the WAN failover.

## Current engineering limits

- No ROM source tree, Android platform SDK, Java compiler or Pixel hardware was
  available in this development environment, so compilation and runtime
  behavior are **unverified**. The first integration build may need small API
  adjustments for the exact 23.2 branch.
- Ethernet detection currently matches `ethN` and `enx...` interface names.
  Some adapters use another name. Record `ip link` on your Pixel and adapt
  `ethernetPresent()` if needed.
- This prototype has no remote health endpoint, cellular reset, watchdog or
  guaranteed automatic cold power-on. Those require device-specific testing.
- No OTA channel or automated release signing is included. Maintaining a
  custom ROM entails timely security updates and signed rollouts.

## Source and license

The code uses Android platform APIs and the Java standard library only.
See `LICENSE` for terms. No credentials or destination numbers are committed.
