module org.dynamisengine.audio.backend.wasapi {
    requires org.dynamisengine.audio.api;

    provides org.dynamisengine.audio.api.device.AudioBackend
        with org.dynamisengine.audio.backend.wasapi.WasapiBackend;
}
