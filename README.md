# ConvoKit Android SDK example

A public native Android example that consumes the compiled ConvoKit SDK from
the public Maven feed at <https://maven.convokit.app>. It does not include or
copy the private SDK source.

The app demonstrates:

- adding `app.convokit:convokit-android` from the unauthenticated Maven feed;
- obtaining a user token from an application-owned backend;
- joining a chatroom by ID through that backend;
- loading message history and sending messages;
- collecting realtime messages and read receipts with Kotlin `Flow`.

## Run

Open this repository in Android Studio and run the `app` configuration, or:

```bash
./gradlew :app:assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

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
