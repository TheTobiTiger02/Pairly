# Privacy policy

Pairly connects your own Android phones to your own Windows PC over your local network. It has no accounts, no cloud service, no analytics, and no advertising.

## What the apps access

To provide the features you turn on, the apps access the following on your devices:

- **Phone:** SMS messages, notifications, contacts, call state, photos and videos, files you choose to send, clipboard contents, and the screen when you start screen mirroring.
- **PC:** clipboard contents, files you choose to send, and the pairing information for your phones.

Each feature asks for its Android permission before it is used, and you can revoke permissions at any time in Android settings.

## Where your data goes

Data moves only between your paired phone and your PC, over your local network, through an encrypted connection that is pinned to your PC's certificate. It is never sent to the developer or any third party.

Data is stored only on your devices: the PC keeps pairing data protected with Windows DPAPI and short-lived caches for photos and clipboard transfers; the phone keeps the trusted PC encrypted with Android Keystore.

## Network requests outside your local network

- The Android app checks GitHub (`api.github.com`) for new releases and downloads updates from GitHub. These requests contain no personal data beyond what any web request reveals, such as your IP address. See GitHub's privacy statement.
- The Windows app is updated by the Microsoft Store.

## Deleting your data

Uninstalling the apps removes their stored data. On the phone, **Settings → Forget PC** removes the pairing without uninstalling.

## Contact

Questions: open an issue at https://github.com/TheTobiTiger02/Pairly/issues.
