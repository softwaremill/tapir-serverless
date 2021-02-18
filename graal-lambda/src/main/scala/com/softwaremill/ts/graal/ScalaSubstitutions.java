package com.softwaremill.ts.graal;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

@TargetClass(className = "scala.runtime.Statics")
final class Target_scala_runtime_Statics {

    @Substitute
    public static void releaseFence() {
        com.softwaremill.ts.graal.UnsafeUtils.UNSAFE.storeFence();
    }
}

public class ScalaSubstitutions {
}
