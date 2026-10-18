# Vaayil privacy policy

Last updated: 2026-10-17

Vaayil is a WireGuard VPN client for Android, developed and published by its maintainer as the open-source project at https://github.com/ATGPlatforms/vaayil. It runs no servers, has no accounts, and sends no analytics. All features are free, with no trial limit or in-app purchases. This policy explains what the app does with data on your device.

## What Vaayil stores on your device

- VPN profiles you import or type in: interface keys, addresses, peer public keys, endpoints, DNS settings, and per-app routing rules. Private and preshared keys are encrypted at rest with a key held in the Android Keystore that never leaves the device, when the device's Keystore is available. If it isn't, Vaayil stores the keys unencrypted and says so on the Profiles screen. Encryption at rest protects the database file if it is copied off the device; it does not protect against a rooted or otherwise compromised device.
- Automation rules: the trusted Wi-Fi network names you enter, the on/off switches, and the external-control token if you enable that option.

Vaayil uploads none of this. Android cloud backup and device-to-device transfer are turned off for the app because encrypted keys cannot be moved; use the Backup screen to export a snapshot yourself.

## Location

If you turn on "Auto-connect on untrusted Wi-Fi", Vaayil asks for location set to "Allow all the time", because Android only reveals the current Wi-Fi network's name to apps with location access, and the guard runs in the background. Vaayil uses this access only to read the current Wi-Fi network's name and security type, including while the app is closed. On Android 11 and older, it also reads the list of nearby Wi-Fi networks, to find the connected network's security type. It does not read GPS coordinates, keeps no history of networks, and never transmits location or network names. Leave the guard off and the permission is never requested.

## Installed apps and camera

- Installed apps: the Split Tunnel tab lists the apps on your device so you can choose which ones use the tunnel. Vaayil reads that list on the device only and never sends it anywhere.
- Camera: used only to scan a QR code when you import a profile. The frames are decoded on the device and are never stored or sent.

## Network connections the app makes

- Your VPN endpoint: the WireGuard server configured in the profile. If the endpoint is a hostname, your current network's resolver looks it up each time the tunnel starts.
- The DNS resolvers configured in the profile: plain DNS, DNS over TLS, or DNS over HTTPS servers. Encrypted DNS travels inside the tunnel. Domains you set to bypass the tunnel are resolved by your current network's resolver, and a DNS-over-TLS or DNS-over-HTTPS server given as a hostname is looked up through that resolver when the tunnel starts, and again after a network change or a failure.

There are no other connections: no crash reporting, no analytics, no update checks.

## Data you export

Snapshot files (.vylconf) are encrypted with a passphrase you choose. Exported .conf files and QR codes are not encrypted and contain your private key; treat them as secrets.

## Children

Vaayil is not directed to children under 13 and collects no personal data from anyone.

## Changes

Updates to this policy ship with the app and are dated at the top.

## Contact

For questions, use the project's issue tracker at https://github.com/ATGPlatforms/vaayil/issues, or the developer contact on the store listing you installed Vaayil from.
