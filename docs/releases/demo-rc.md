This is **Stashd Demo**, a signed sample-data build for testing the download workflow. It uses package `com.abhranil.stashd.demo` and can be installed alongside the real app. It does not sign in to Google or download real Drive files.

It writes generated sample files into the folder you choose. The APK uses Stashd's permanent release certificate; future demo updates use the same key. SHA256SUMS.txt and the public certificate are attached.

This is a prerelease. Validation details are recorded in docs/DEPLOY.md at the release commit. Live Google consent/Drive behavior and acceptance on the owner's physical phone must be completed before a real-use release is called ready.
