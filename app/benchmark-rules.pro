# The benchmark build type's (app/build.gradle.kts): R8 optimizes as for a release, but removes
# nothing, so the instrumented tests run against it find every member they call (a test-only one,
# TextKeyboard.forgetScene, would be gone). Names are kept already (proguard-rules.pro).
-dontshrink
# kept by -dontshrink only, what nothing the app runs reaches refers to classes not in the app
-dontwarn javax.lang.model.element.Modifier
-dontwarn splitties.init.AppCtxKt
