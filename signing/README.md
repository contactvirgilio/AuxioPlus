# Release signing key

`auxioplus-release.jks` is the key every AuxioPlus release APK is signed with, so new builds
install as updates over old ones.

It is encrypted (PKCS#12, AES-256) with a long random password that is **not** in this repository.
The build workflow reads that password from the `KEYSTORE_PASSWORD` repository secret
(Settings > Secrets and variables > Actions). Without the password the file is useless.

Keep a copy of the password somewhere safe: if it is lost, future builds can't be signed with this
key and the app has to be uninstalled and reinstalled once with a new key.
