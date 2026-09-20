package com.limelight.shadows;

import android.app.GameManager;
import android.app.GameState;
import android.content.Context;
import android.os.Handler;

import org.robolectric.annotation.ClassName;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

@Implements(value = GameManager.class, isInAndroidSdk = true)
public class ShadowGameManager {

    @Implementation(maxSdk = 35)
    protected void __constructor__(Context context, Handler handler) {
        // no-op constructor to avoid ServiceManager lookup
    }

    @Implementation(minSdk = 36)
    protected void __constructor__(Context context,
            @ClassName("android.app.IGameManagerService") Object service) {
        // Newer SDKs receive the binder service directly; keep tests independent of it.
    }

    @Implementation
    protected void setGameState(GameState state) {
        // stub method – nothing to do in tests
    }
}
