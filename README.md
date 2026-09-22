# ConvoKit Android SDK example

A public native Android example that consumes the compiled ConvoKit SDK and UI library from
the public Maven feed at <https://maven.convokit.app>. It does not include or
copy the private SDK source.

The app demonstrates:

- adding `app.convokit:convokit-android` from the unauthenticated Maven feed;
- obtaining a user token from an application-owned backend;
- joining a chatroom by ID through that backend;
- embedding `ConvoKitConversation` from `app.convokit:convokit-android-ui` in an Android Views application with `ComposeView`;
- delegating message history, sending, editing and deleting your own messages, quoted replies and jump-to-message, media rendering, typing and read receipts to the reusable UI library;
- calling the 0.9.0 core surface directly from the host: sending with `replyToMessageId`, resolving a whole page of quoted rows with one `getReplyPreviews` call, centring a window with `getMessageContext`, and telling the coded `MESSAGE_NOT_FOUND` and `CONVERSATION_NOT_FOUND` answers apart;
- creating an adapter after each successful login and disposing the old conversation before disconnecting or switching users.

The sample no longer maintains a second message renderer, composer, or raw
Realtime collector; the quoted-reply panel under the room is a report of what the
core answered, not a second renderer. Room joining and token issuance remain
host/backend concerns, not responsibilities of the UI package. The separate
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

### Published 0.9.0 SDKs

The example consumes the published 0.9.0 core and UI from maven.convokit.app.
The embedded room acknowledges a concrete message for precise read positions,
defers acknowledgements while the host is hidden, correlates pending sends
with live/history confirmations before HTTP returns, and sends the caller's
own `Conversation.membership.privateStateVersion` with every targeted
acknowledgement so a private "mark unread" marker set from another device
survives it. Since 0.8.0 the room also lets the connected user edit and
delete their own confirmed messages through the library's default rows and
composer, with no host code: a long press on one of your own rows (accessible
action `Message actions`) opens `Edit message` / `Delete message`. Editing
prefills the composer with the original text under an `Editing message`
banner (the unsent draft is stashed and restored on `Cancel` or after a
successful save) and the `Save` button sends the row's `revision` through the
core's author-tier `editMessage(messageId, text, revision)`
(`PATCH /api/v1/messages/:id/own`); attachments are never changed by an edit
and clearing the field saves `null` to remove a caption. When another device
edited the same message first the backend answers 409 `REVISION_CONFLICT`:
the room reloads the row, shows its current content, keeps the typed text and
retries with the fresh revision on the next save. Deleting confirms
(`Delete this message?`) before calling `deleteMessage(messageId)`
(`DELETE /api/v1/messages/:id/own`); the removal cannot be undone, other
devices drop the row through the room's deletion notification, and files
already received cannot be retracted. Edited rows show `Edited` beside the
time from `Message.isEdited` (`revision > 0`), never from `updatedAt`. Both
author routes require the 0.8 backend. The library's SDK-backed conversation
list pages `listInbox` (`GET /api/v1/inbox`), renders previews, activity times
and unread badges by itself (a numberless dot named `Unread` for a room that
is unread only through its marker) and exposes `markUnread`/`clearUnread` on
its controller; this sample embeds only the room, so see the
[UI showcase](https://github.com/ConvoKitApp/ConvoKit-Android-UI-Examples)
for that list, its "Mark unread" affordance and the custom row and composer
variants that read `Message.isEdited` and edit mode from the controller.

Since 0.9.0 the room also quotes messages and jumps to a quoted one, again with
no host code: the long-press menu gains `Reply`, the composer shows a
cancellable `Replying to` strip, and a reply renders a `Quoted message` block
above its text carrying the quoted author and an excerpt,
`Original message unavailable` once the original is gone, or the bare reference
while it is still resolving. Activating that block (`Go to quoted message`)
centres the room on the original, highlights it for about two seconds and
offers a way back to the latest. The quoted text is never copied onto the
reply — it is re-read — so an edit of the original shows through and deleting
the original leaves the reference in place. Both reads need the 0.9 backend.

Under the room this sample adds a host-owned panel that drives the same 0.9.0
core surface directly, because an application that embeds only the core has to
drive it itself. `Run the quoted-reply demo` sends one message with
`SendMessageInput.replyToMessageId` set to the newest row — the reference is
written once, so no edit can move it and deleting the quoted message leaves it
in place — then resolves every `Message.replyToMessageId` on the page with a
single `getReplyPreviews(conversationId, messageIds)` call. The SDK
de-duplicates those ids in first-seen order and splits them into requests of at
most 50, and an id that is absent from a resolved answer is the only deletion
signal, never an error: keep the reference and render "Original message
unavailable". It then centres a window with
`getMessageContext(conversationId, messageId = …)`, whose `olderCursor` and
`newerCursor` are always present and nullable, a null newer cursor meaning the
window touched the newest message as of the query. Finally it runs the two
failures a host has to tell apart: an unknown `messageId` answers 404
`MESSAGE_NOT_FOUND`, meaning the target does not exist, was deleted or belongs
to another room, and a room the user is not an active member of answers 404
`CONVERSATION_NOT_FOUND` from the membership check that runs before any message
id is read. A 404 with no code at all *from either of those two routes* is
neither — it is a backend older than 0.9 that has neither route, so hide the
affordances rather than retrying. On the routes that predate 0.9, reading
history and sending, an uncoded 404 is the shipped membership answer and says
nothing about the backend's version, so the panel reports those two separately.
Running the demo posts a message to the room you joined.

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
