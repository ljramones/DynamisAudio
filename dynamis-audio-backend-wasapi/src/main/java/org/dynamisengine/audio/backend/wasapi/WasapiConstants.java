package org.dynamisengine.audio.backend.wasapi;

/**
 * WASAPI / COM constants from Windows SDK headers.
 *
 * Sources: audioclient.h, mmdeviceapi.h, Audiosessiontypes.h, objbase.h
 */
final class WasapiConstants {

    private WasapiConstants() {}

    // -- HRESULT codes -------------------------------------------------------

    static final int S_OK = 0;
    static final int S_FALSE = 1;

    /** AUDCLNT_E_DEVICE_INVALIDATED */
    static final int AUDCLNT_E_DEVICE_INVALIDATED = 0x88890004;

    /** AUDCLNT_E_NOT_INITIALIZED */
    static final int AUDCLNT_E_NOT_INITIALIZED = 0x88890001;

    // -- CoInitializeEx flags ------------------------------------------------

    /** COINIT_MULTITHREADED */
    static final int COINIT_MULTITHREADED = 0x0;

    // -- CLSCTX --------------------------------------------------------------

    /** CLSCTX_ALL */
    static final int CLSCTX_ALL = 0x17;

    // -- EDataFlow -----------------------------------------------------------

    /** eRender */
    static final int eRender = 0;

    // -- ERole ---------------------------------------------------------------

    /** eConsole */
    static final int eConsole = 0;

    // -- AUDCLNT_SHAREMODE ---------------------------------------------------

    /** AUDCLNT_SHAREMODE_SHARED */
    static final int AUDCLNT_SHAREMODE_SHARED = 0;

    /** AUDCLNT_SHAREMODE_EXCLUSIVE */
    static final int AUDCLNT_SHAREMODE_EXCLUSIVE = 1;

    // -- AUDCLNT_STREAMFLAGS -------------------------------------------------

    /** AUDCLNT_STREAMFLAGS_EVENTCALLBACK — event-driven buffer delivery */
    static final int AUDCLNT_STREAMFLAGS_EVENTCALLBACK = 0x00040000;

    /** AUDCLNT_STREAMFLAGS_NOPERSIST */
    static final int AUDCLNT_STREAMFLAGS_NOPERSIST = 0x00080000;

    // -- WAVEFORMATEX constants ----------------------------------------------

    /** WAVE_FORMAT_IEEE_FLOAT */
    static final short WAVE_FORMAT_IEEE_FLOAT = 0x0003;

    /** WAVE_FORMAT_EXTENSIBLE */
    static final short WAVE_FORMAT_EXTENSIBLE = (short) 0xFFFE;

    // -- KSDATAFORMAT_SUBTYPE_IEEE_FLOAT GUID bytes --------------------------
    // {00000003-0000-0010-8000-00AA00389B71}
    static final byte[] KSDATAFORMAT_SUBTYPE_IEEE_FLOAT = {
            0x03, 0x00, 0x00, 0x00,
            0x00, 0x00,
            0x10, 0x00,
            (byte) 0x80, 0x00,
            0x00, (byte) 0xAA, 0x00, 0x38, (byte) 0x9B, 0x71
    };

    // -- COM vtable method indices -------------------------------------------
    // IUnknown: QueryInterface=0, AddRef=1, Release=2
    // IMMDeviceEnumerator: EnumAudioEndpoints=3, GetDefaultAudioEndpoint=4, ...
    // IMMDevice: Activate=3, ...
    // IAudioClient: Initialize=3, GetBufferSize=4, GetStreamLatency=5,
    //               GetCurrentPadding=6, IsFormatSupported=7, GetMixFormat=8,
    //               GetDevicePeriod=9, Start=10, Stop=11, Reset=12,
    //               SetEventHandle=13, GetService=14
    // IAudioRenderClient: GetBuffer=3, ReleaseBuffer=4

    static final int IUNKNOWN_RELEASE = 2;

    static final int IMMDEVICEENUMERATOR_GETDEFAULTAUDIOENDPOINT = 4;
    static final int IMMDEVICEENUMERATOR_ENUMAUDIOENDPOINTS = 3;

    static final int IMMDEVICE_ACTIVATE = 3;
    static final int IMMDEVICE_OPENPROPERTYSTORE = 4;

    static final int IAUDIOCLIENT_INITIALIZE = 3;
    static final int IAUDIOCLIENT_GETBUFFERSIZE = 4;
    static final int IAUDIOCLIENT_GETSTREAMLATENCY = 5;
    static final int IAUDIOCLIENT_GETCURRENTPADDING = 6;
    static final int IAUDIOCLIENT_ISFORMATSUPPORTED = 7;
    static final int IAUDIOCLIENT_GETMIXFORMAT = 8;
    static final int IAUDIOCLIENT_GETDEVICEPERIOD = 9;
    static final int IAUDIOCLIENT_START = 10;
    static final int IAUDIOCLIENT_STOP = 11;
    static final int IAUDIOCLIENT_RESET = 12;
    static final int IAUDIOCLIENT_SETEVENTHANDLE = 13;
    static final int IAUDIOCLIENT_GETSERVICE = 14;

    static final int IAUDIORENDERCLIENT_GETBUFFER = 3;
    static final int IAUDIORENDERCLIENT_RELEASEBUFFER = 4;

    // -- DEVICE_STATE --------------------------------------------------------

    /** DEVICE_STATE_ACTIVE */
    static final int DEVICE_STATE_ACTIVE = 0x00000001;
}
