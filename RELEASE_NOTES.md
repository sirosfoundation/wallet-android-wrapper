# 0.1.16

- Fix handling of oversized incoming BLE notifications.
- Encrypt user settings for in-depth defence.
- Improved DC-API support with multiple tenants used.
- Fixed crashes during collection of debug logs.

# 0.1.15

- Added DC-API support.

# 0.1.14

- Improved FaceTec support. Don't let app die, when FaceTec lib doesn't work.

# 0.1.13

- Added rage-shake debug menu to extract logs also in released apps. 

# 0.1.12

- New version to add FaceTec support to Github-Action releases.

# 0.1.11

- Added support for creating digital credentials from physical IDs.
- Updated dependencies.

# 0.1.10

- Improved YubiKey login even more, by simplifying the USB discovery of still plugged in keys.
- Fixed handling of `http` URLs delivered by the OS. (Typically happens, when manually entering URLs.)

# 0.1.9

- Improved YubiKey login:
  - Remember PIN for 1 minute to avoid forcing the user to re-enter it up to 3 times for a login.
  - Reuse a still plugged in USB YubiKey to avoid forcing the user to replug up to 3 times for a login.

# 0.1.8

- Fixed reception of new credential.
- Updated dependencies.

# 0.1.7

- Fixed app display name.
- Updated dependencies.
- Fixed recursion in injected JavaScript.

# 0.1.6

- Fixes debug shortcuts.
- Refactored support for debugging with multiple instances of wwWallet: Use a single source of truth.
- Only show shortcuts in debug build.

# 0.1.5

- Updated dependencies and SDK.
- Switch to new web app location: https://id.siros.org
- Reworked YubiKit SDK interception: Leave alone, only intercept, if `security-key` was selected.
- Fixed WebView link handling: Show everything, which is *not* in id.siros.org in the browser 
  instead of inside the wrapper app.

# 0.1.4

- Fixed complete JS breakdown because of illegal `innerHTML` calls in conjunction with 
  `require-trusted-types-for` Content-Security-Policy.
- Updated dependencies and SDK.
- Fixed `LocalContainer` not adhering to specifications.
- Appeased ktlintcheck.

# 0.1.3

* Replaced app icon with new SIROS logo.

# 0.1.2

* hotterfix: use signing extension sdk

# 0.1.1

* hotfix: add signing extension sdk

# 0.1.0

* passkeys provider
* sign extension
* minor bug fixes

# 0.0.16

* release version preparation
 * playstore release and on github
* rework external url handling (`openid4vp://`, `haip://`)
* persist baseurl across sessions
  * use app shortcuts (longpress on app icon)
* add feature debug only passkey provider

# 0.0.15

* stability improvements
* add 'hints' to select from platform authenticator (android sdk) or security key authenticator (yubikit sdk).
* align night / day mode with phone selection
* add credential selection for multiples
* cleanup
 * use debug build for debug menu and issue reporting

# 0.0.14

* fix for PRF extension
* github action for building, testing and releasing
 * available: release, debug, yubikit apks.

# 0.0.13

* Fix back button behaviour
 * update state after `history.back()` got executed, otherwise compose doesn't pickup the next state change
* Add handling of external links
  * GitHub and Gunet open default browser now
* minor polishing

# 0.0.12

* Slowdown of BLE communication
* Polish build system (on version to change)
* see [screencast](media/wwrapper-ble-presentment.mov) for setup of [App Verifier](https://install.appcenter.ms/orgs/eu-digital-identity-wallet/apps/mdoc-verifier-testing/distribution_groups/eudi%20verifier%20(testing)%20public).


# 0.0.11

* Invoke wallet over default browser ('app links')

# 0.0.10

* Convert incoming js arrays into correct representation for extensions (prf)

# 0.0.0 - 0.0.10

// internal development versions
