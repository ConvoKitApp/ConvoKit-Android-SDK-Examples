# ConvoKit Android SDK example

A public native Android example that consumes the compiled ConvoKit SDK and UI library from
the public Maven feed at <https://maven.convokit.app>. It does not include or
copy the private SDK source.

The app demonstrates:

- adding `app.convokit:convokit-android` from the unauthenticated Maven feed;
- obtaining a user token from an application-owned backend;
- joining a chatroom by ID through that backend;
- embedding `ConvoKitConversation` from `app.convokit:convokit-android-ui` in an Android Views application with `ComposeView`;
- delegating message history, sending, media rendering, typing and read receipts to the reusable UI library;
- creating an adapter after each successful login and disposing the old conversation before disconnecting or switching users.

The sample no longer maintains a second message renderer, composer, or raw
Realtime collector. Room joining and token issuance remain host/backend concerns,
not responsibilities of the UI package. The separate
[UI showcase](https://github.com/ConvoKitApp/ConvoKit-Android-UI-Examples) demonstrates
more configurable component variants. This sample does not implement a file picker
or an attachment-download destination; provide the UI callbacks for those host features.

## Run

Open this repository in Android Studio and run the `app` configuration, or:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

The host handles system-bar, display-cutout and keyboard insets before embedding
Compose, so padding is not applied twice. Local host tests use Robolectric and
synthetic insets; they do not prove hosted authorization or physical-device chat.

### Published 0.7.0 SDKs

The example consumes the published 0.7.0 core and UI from maven.convokit.app.
The embedded room acknowledges a concrete message for precise read positions,
defers acknowledgements while the host is hidden, and correlates pending sends
with live/history confirmations before HTTP returns. Since 0.7.0 the room also
captures the caller's own `Conversation.membership.privateStateVersion` when it
opens and sends it with every targeted acknowledgement, so a private "mark
unread" marker set from another device after the open survives that
acknowledgement; a marked room with no rendered messages is cleared by one
conditional `clearConversationUnread` instead. The marker is never shown to
other members. The library's SDK-backed conversation list pages `listInbox`
(`GET /api/v1/inbox`), renders previews, activity times and unread badges by
itself (a numberless dot named `Unread` for a room that is unread only through
its marker) and exposes `markUnread`/`clearUnread` on its controller; this
sample embeds only the room, so see the
[UI showcase](https://github.com/ConvoKitApp/ConvoKit-Android-UI-Examples)
for that list and its "Mark unread" affordance.
Public builds require no private-source access or dependency substitution.
No private library source, archive, credentials or implementation is committed here.

The checked-in defaults use the deliberately open ConvoKit demo broker at
<https://convokit-open-chatroom.vercel.app>. Enter any demo user ID and an
existing ConvoKit room ID. The broker keeps the client secret on Vercel while
granting demo membership and returning a short-lived user token.

## Use your own backend

Replace the two public `buildConfigField` values in `app/build.gradle.kts`:

- `CONVOKIT_CLIENT_ID`: your public app client ID;
- `DEMO_BACKEND_URL`: your application's backend, implementing the token and
  optional join endpoints used in `MainActivity`.

The SDK uses the managed `https://api.convokit.app` endpoint automatically.
Pass a custom `backendUrl` only for local testing or a self-hosted deployment.

In production, authenticate your application user in the token endpoint and
derive the app-user ID server-side. Never add `CONVOKIT_CLIENT_SECRET` to an
Android build, resource, manifest, or Gradle property.

## SDK documentation

See <https://convokit.app/docs/android-sdk>.
