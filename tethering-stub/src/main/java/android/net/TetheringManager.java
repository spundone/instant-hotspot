package android.net;

import java.util.concurrent.Executor;

/**
 * Minimal compile-only stand-in. The real class is on the device; some SDKs ship a
 * platform stub jar that omits TetheringManager entirely, which breaks @link compile.
 */
public class TetheringManager {
    public static final int TETHERING_WIFI = 0;

    public TetheringManager() {
    }

    public void startTethering(
            int type,
            boolean showProvisioningUi,
            Executor executor,
            final StartTetheringCallback callback) {
        throw new UnsupportedOperationException("stub");
    }

    public void stopTethering(int type) {
        throw new UnsupportedOperationException("stub");
    }

    public static abstract class StartTetheringCallback {
        public void onTetheringStarted() {
        }

        public void onTetheringFailed(int error) {
        }
    }
}
