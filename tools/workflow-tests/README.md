# Portable presentation contracts

Run the production presentation transaction and swap proof with their existing JUnit tests,
without Android SDK, model assets, native libraries, or a device. Use JDK 25 and the repository
wrapper from the repository root:

```powershell
.\gradlew.bat -p tools/workflow-tests test
```

On Linux or macOS, run `bash ./gradlew -p tools/workflow-tests test`.
The standalone settings file keeps Android plugins and application tasks out of this build.
The same source files and tests remain part of the Android JVM test suite.
