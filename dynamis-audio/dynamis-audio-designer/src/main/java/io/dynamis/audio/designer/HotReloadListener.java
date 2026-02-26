package io.dynamis.audio.designer;

/**
 * Callback interface for hot-reload completion notifications.
 *
 * Registered with HotReloadManager. Called on the reload worker thread
 * after a successful reload cycle completes.
 *
 * ALLOCATION CONTRACT: Implementations should not allocate heavily —
 * the callback fires on the reload thread, not the game or DSP thread.
 */
public interface HotReloadListener {

    /**
     * Called after a hot-reload cycle completes successfully.
     *
     * @param resourceType the category of resource that was reloaded
     * @param resourceName the specific resource name or identifier
     */
    void onReloaded(HotReloadManager.ResourceType resourceType, String resourceName);

    /**
     * Called if a hot-reload attempt fails.
     *
     * @param resourceType the category that failed
     * @param resourceName the resource name
     * @param reason       human-readable failure description
     */
    void onReloadFailed(HotReloadManager.ResourceType resourceType,
                        String resourceName, String reason);
}
