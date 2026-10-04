package com.blogsystem.ai.application.knowledge;

import com.blogsystem.ai.domain.knowledge.IndexBuild;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RebuildIndexHandler {
    private final Map<String, IndexBuild> builds = new ConcurrentHashMap<>();

    public IndexBuild start() {
        IndexBuild build = IndexBuild.queue("manual");
        builds.put(build.id(), build);
        build.start();
        // The actual vector sync worker will complete this aggregate in the next phase.
        return build;
    }

    public Optional<IndexBuild> find(String id) {
        return Optional.ofNullable(builds.get(id));
    }
}
