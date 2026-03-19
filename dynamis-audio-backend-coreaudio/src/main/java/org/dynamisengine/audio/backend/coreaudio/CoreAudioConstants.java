package org.dynamisengine.audio.backend.coreaudio;

/**
 * CoreAudio / AudioToolbox constants extracted from macOS SDK headers.
 *
 * These correspond to values defined in:
 *   AudioUnit/AudioUnit.h
 *   AudioToolbox/AudioComponent.h
 *   CoreAudio/AudioHardware.h
 *   CoreAudioTypes/CoreAudioBaseTypes.h
 */
final class CoreAudioConstants {

    private CoreAudioConstants() {}

    // -- OSStatus codes -------------------------------------------------------

    static final int kAudio_NoError = 0;

    // -- AudioComponent types -------------------------------------------------

    /** kAudioUnitType_Output */
    static final int kAudioUnitType_Output = 0x61756F75; // 'auou'

    /** kAudioUnitSubType_DefaultOutput */
    static final int kAudioUnitSubType_DefaultOutput = 0x64656620; // 'def '

    /** kAudioUnitSubType_HALOutput — low-level HAL output unit */
    static final int kAudioUnitSubType_HALOutput = 0x6168616C; // 'ahal'

    /** kAudioUnitManufacturer_Apple */
    static final int kAudioUnitManufacturer_Apple = 0x6170706C; // 'appl'

    // -- AudioUnit properties -------------------------------------------------

    /** kAudioUnitProperty_StreamFormat */
    static final int kAudioUnitProperty_StreamFormat = 8;

    /** kAudioUnitProperty_SetRenderCallback */
    static final int kAudioUnitProperty_SetRenderCallback = 23;

    /** kAudioOutputUnitProperty_CurrentDevice */
    static final int kAudioOutputUnitProperty_CurrentDevice = 2000;

    // -- AudioUnit scope ------------------------------------------------------

    /** kAudioUnitScope_Input (for output unit, this is the input to the output stage) */
    static final int kAudioUnitScope_Input = 1;

    /** kAudioUnitScope_Output */
    static final int kAudioUnitScope_Output = 0;

    /** kAudioUnitScope_Global */
    static final int kAudioUnitScope_Global = 0;

    // -- AudioStreamBasicDescription format -----------------------------------

    /** kAudioFormatLinearPCM */
    static final int kAudioFormatLinearPCM = 0x6C70636D; // 'lpcm'

    /** kAudioFormatFlagIsFloat */
    static final int kAudioFormatFlagIsFloat = 1;

    /** kAudioFormatFlagIsPacked */
    static final int kAudioFormatFlagIsPacked = 8;

    /** kAudioFormatFlagIsNonInterleaved */
    static final int kAudioFormatFlagIsNonInterleaved = 32;

    // -- AudioHardware properties --------------------------------------------

    /** kAudioHardwarePropertyDevices */
    static final int kAudioHardwarePropertyDevices = 0x64657623; // 'dev#'

    /** kAudioHardwarePropertyDefaultOutputDevice */
    static final int kAudioHardwarePropertyDefaultOutputDevice = 0x646F7574; // 'dout'

    /** kAudioObjectSystemObject */
    static final int kAudioObjectSystemObject = 1;

    /** kAudioDevicePropertyDeviceNameCFString */
    static final int kAudioDevicePropertyDeviceNameCFString = 0x6C6E616D; // 'lnam'

    /** kAudioDevicePropertyStreamConfiguration */
    static final int kAudioDevicePropertyStreamConfiguration = 0x736C6179; // 'slay'

    /** kAudioDevicePropertyNominalSampleRate */
    static final int kAudioDevicePropertyNominalSampleRate = 0x6E737274; // 'nsrt'

    /** kAudioDevicePropertyAvailableNominalSampleRates */
    static final int kAudioDevicePropertyAvailableNominalSampleRates = 0x6E737223; // 'nsr#'

    /** kAudioDevicePropertyBufferFrameSize */
    static final int kAudioDevicePropertyBufferFrameSize = 0x6673697A; // 'fsiz'

    // -- AudioObjectPropertyScope & Element -----------------------------------

    /** kAudioObjectPropertyScopeOutput */
    static final int kAudioObjectPropertyScopeOutput = 0x6F757470; // 'outp'

    /** kAudioObjectPropertyScopeGlobal */
    static final int kAudioObjectPropertyScopeGlobal = 0x676C6F62; // 'glob'

    /** kAudioObjectPropertyElementMain */
    static final int kAudioObjectPropertyElementMain = 0;

    // -- AudioObjectPropertyAddress selectors --------------------------------

    /** kAudioObjectPropertyScopeWildcard */
    static final int kAudioObjectPropertyScopeWildcard = 0x2A2A2A2A; // '****'

    /** kAudioObjectPropertyElementWildcard */
    static final int kAudioObjectPropertyElementWildcard = 0xFFFFFFFF;
}
