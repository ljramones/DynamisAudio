package io.dynamis.audio.dsp.device;

/**
 * Thrown when an AudioDevice operation fails at the platform level.
 *
 * Carries a platform-specific error code for diagnostic purposes.
 * The errorCode is platform-dependent: HRESULT on Windows, OSStatus on macOS,
 * errno on Linux. Use 0 if not applicable.
 */
public final class AudioDeviceException extends Exception {

    private final int errorCode;

    public AudioDeviceException(String message) {
        super(message);
        this.errorCode = 0;
    }

    public AudioDeviceException(String message, int errorCode) {
        super(message + " (error code: 0x" + Integer.toHexString(errorCode) + ")");
        this.errorCode = errorCode;
    }

    public AudioDeviceException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = 0;
    }

    public AudioDeviceException(String message, int errorCode, Throwable cause) {
        super(message + " (error code: 0x" + Integer.toHexString(errorCode) + ")", cause);
        this.errorCode = errorCode;
    }

    /** Platform-specific error code. 0 if not applicable. */
    public int errorCode() { return errorCode; }
}
