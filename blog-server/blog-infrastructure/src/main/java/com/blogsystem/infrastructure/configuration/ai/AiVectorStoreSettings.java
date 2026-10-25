package com.blogsystem.infrastructure.configuration.ai;

import java.nio.file.Path;
import java.util.Objects;

/** Bootstrap supplies the established persistence location; providers do not depend on app config. */
public record AiVectorStoreSettings(Path storePath) {
    public AiVectorStoreSettings {
        Objects.requireNonNull(storePath, "storePath");
    }
}
