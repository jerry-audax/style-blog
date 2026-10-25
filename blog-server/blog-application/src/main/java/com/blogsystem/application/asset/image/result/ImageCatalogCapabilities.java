package com.blogsystem.application.asset.image.result;

/** Image protocol is independent of music/video uploads and tracked deletion. */
public record ImageCatalogCapabilities(String storage, String uploadProtocol, boolean uploadEnabled,
        boolean deleteEnabled, long avatarBytes, long imageBytes) {
    public static ImageCatalogCapabilities postgres(boolean uploadEnabled) {
        return new ImageCatalogCapabilities("postgres", uploadEnabled ? "tracked-image-v1" : null,
                uploadEnabled, false, 2097152, 10485760);
    }
    public static ImageCatalogCapabilities legacy() {
        return new ImageCatalogCapabilities("legacy-json", "legacy", true, true, 2097152, 10485760);
    }
}
