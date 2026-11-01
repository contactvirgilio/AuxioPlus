# Release signing key

`auxioplus-release.jks` is the key every AuxioPlus release APK is signed with, so new builds
install as updates over old ones. Its password is in `keystore-password.txt`, which the build
workflow reads (a `KEYSTORE_PASSWORD` repository secret, if set, takes priority).

Because this repository is public, anyone can read both and sign their own APK that Android would
accept as an update to AuxioPlus. Only install AuxioPlus APKs that come from this repository's
Actions runs or releases.
