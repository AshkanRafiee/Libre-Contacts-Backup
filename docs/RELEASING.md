# Releasing Libre Contacts Backup

## Production signing key

Generate a new key once. Keep the keystore and both passwords offline and backed up:

```bash
keytool -genkeypair \
  -v \
  -keystore app/librecontacts-release.jks \
  -storetype JKS \
  -alias librecontacts \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

Create the ignored local `signing.properties` file:

```properties
storeFile=app/librecontacts-release.jks
storeType=JKS
storePassword=YOUR_KEYSTORE_PASSWORD
keyAlias=librecontacts
keyPassword=YOUR_KEY_PASSWORD
```

The local release commands are:

```bash
./gradlew assembleRelease
./gradlew bundleRelease
```

Never commit `*.jks` or `signing.properties`. Losing the production keystore prevents updates to the same Android application identity.

## GitHub Releases

Create these GitHub repository **Actions secrets**:

- `LIBRECONTACTS_KEYSTORE_BASE64`
- `LIBRECONTACTS_KEYSTORE_PASSWORD`
- `LIBRECONTACTS_KEY_ALIAS`
- `LIBRECONTACTS_KEY_PASSWORD`

Encode the keystore for the first secret on Linux/macOS:

```bash
base64 -w 0 app/librecontacts-release.jks
```

On macOS, use:

```bash
base64 app/librecontacts-release.jks | tr -d '\n'
```

All four secrets must come from the same keystore. If GitHub reports `Given final block not properly padded`, replace the four secrets together. Verify the values locally first:

```bash
keytool -importkeystore -noprompt \
  -srckeystore app/librecontacts-release.jks \
  -srcstoretype JKS \
  -srcstorepass YOUR_KEYSTORE_PASSWORD \
  -srcalias librecontacts \
  -srckeypass YOUR_KEY_PASSWORD \
  -destkeystore /tmp/librecontacts-verified.jks \
  -deststorepass validation-only \
  -destkeypass validation-only
```

For PowerShell, create the base64 secret value with:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("app/librecontacts-release.jks"))
```

The release workflow runs only when a GitHub Release is published. Create the release from a tag beginning with `v`:

```bash
git tag v1.0
git push origin v1.0
```

Then create and publish a GitHub Release for the `v1.0` tag. The workflow builds the signed APK and AAB, creates SHA-256 checksums, and uploads them to that published release. Ordinary pushes and tags do not publish release artifacts.

Increment `versionCode` in `app/build.gradle` for every future release.

## F-Droid

F-Droid builds and signs the app itself. Do not give F-Droid your production keystore.

1. Push the source repository and a tagged release such as `v1.0`.
2. Ensure the GPLv3 `LICENSE` file is present.
3. Copy `fdroid/com.librecontacts.backup.yml` into the `metadata/` directory of the `fdroiddata` repository.
4. Submit the metadata through the F-Droid issue tracker:
   `https://gitlab.com/fdroid/fdroiddata/-/issues`
5. Respond to the F-Droid maintainer review and fix any metadata or reproducible-build findings.

The F-Droid metadata uses the source tag and builds the unsigned source release. F-Droid applies its own signing process.
