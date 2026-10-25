package com.blogsystem.trigger.http.asset;

import com.blogsystem.application.asset.deletion.command.MediaDeletionCommand;
import com.blogsystem.domain.asset.deletion.model.MediaDeletionException;
import com.blogsystem.domain.asset.deletion.model.valobj.MediaDeletionOwnership;
import com.blogsystem.domain.asset.deletion.model.valobj.MediaDeletionTarget;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Strict transport parsing: versions are decimal strings, never JSON numbers. */
public final class MediaDeletionRequests {
    private MediaDeletionRequests() {}

    public record Preview(JsonNode target) {
        @JsonAnySetter
        public void unknown(String field, JsonNode value) {
            throw invalid();
        }

        public MediaDeletionTarget domain() {
            return parseTarget(target);
        }
    }

    public record Submit(JsonNode target, JsonNode expectedVersion, JsonNode assetVersions) {
        @JsonAnySetter
        public void unknown(String field, JsonNode value) {
            throw invalid();
        }

        public MediaDeletionCommand domain() {
            long version = version(expectedVersion);
            if (assetVersions == null || !assetVersions.isObject() || assetVersions.size() > 3)
                throw invalid();
            Map<UUID, Long> versions = new HashMap<>();
            assetVersions
                    .fields()
                    .forEachRemaining(e -> versions.put(uuid(e.getKey()), version(e.getValue())));
            return new MediaDeletionCommand(parseTarget(target), version, versions);
        }
    }

    public record Ownership(JsonNode scope, JsonNode expectedVersion, JsonNode note) {
        @JsonAnySetter
        public void unknown(String field, JsonNode value) {
            throw invalid();
        }

        public MediaDeletionOwnership.Scope parsedScope() {
            return MediaDeletionOwnership.Scope.valueOf(text(scope));
        }

        public long version() {
            return MediaDeletionRequests.version(expectedVersion);
        }

        public String parsedNote() {
            return note == null || note.isNull() ? null : text(note);
        }
    }

    static MediaDeletionTarget parseTarget(JsonNode node) {
        if (node == null || !node.isObject()) throw invalid();
        node.fieldNames()
                .forEachRemaining(
                        key -> {
                            if (!Set.of("kind", "id", "slot").contains(key)) throw invalid();
                        });
        var kind = MediaDeletionTarget.Kind.valueOf(text(node.get("kind")));
        var slot = node.get("slot");
        return new MediaDeletionTarget(
                kind,
                text(node.get("id")),
                slot == null || slot.isNull()
                        ? null
                        : MediaDeletionTarget.Slot.valueOf(text(slot)));
    }

    static String text(JsonNode node) {
        if (node == null || !node.isTextual()) throw invalid();
        return node.textValue();
    }

    static long version(JsonNode node) {
        if (node == null || node.isNull())
            throw new MediaDeletionException(MediaDeletionException.Reason.MISSING_VERSION);
        String value = text(node);
        if (!value.matches("0|[1-9][0-9]{0,18}")) throw invalid();
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            throw invalid();
        }
    }

    static UUID uuid(String value) {
        if (value == null
                || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw invalid();
        return UUID.fromString(value);
    }

    static MediaDeletionException invalid() {
        return new MediaDeletionException(MediaDeletionException.Reason.INVALID_REQUEST);
    }
}
