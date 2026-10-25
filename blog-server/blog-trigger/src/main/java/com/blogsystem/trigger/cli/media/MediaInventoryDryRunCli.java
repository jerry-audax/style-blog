package com.blogsystem.trigger.cli.media;

import com.blogsystem.application.asset.inventory.service.MediaInventoryDryRunApplicationService;
import com.blogsystem.domain.asset.inventory.model.valobj.MediaReconciliationStatus;

import java.io.PrintWriter;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Explicit read-only CLI transport; no provider SDK, settings, credentials or repository construction. */
public final class MediaInventoryDryRunCli {
    private final MediaInventoryDryRunApplicationService inventory;
    private final String provider;
    private final URI endpoint;
    private final String root;

    public MediaInventoryDryRunCli(
            MediaInventoryDryRunApplicationService inventory,
            String provider,
            URI endpoint,
            String root) {
        this.inventory = Objects.requireNonNull(inventory);
        this.provider = provider;
        this.endpoint = endpoint;
        this.root = root;
    }

    public int execute(String[] args, PrintWriter output, PrintWriter errors) {
        try {
            Options options = Options.parse(args);
            var result =
                    inventory.preview(
                            options.music(), options.surprise(), options.allowMissingSurprise());
            var preview = result.inventory();
            var kinds = result.requestedKinds();
            boolean complete = result.catalogSnapshotsComplete();
            int code = result.resultCode();
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("mode", "READ_ONLY_DRY_RUN");
            report.put("provider", provider);
            report.put("endpoint", endpoint.toString());
            report.put("rootFolder", root);
            report.put("scanStartedAt", result.scanStartedAt().toString());
            report.put("scanFinishedAt", result.scanFinishedAt().toString());
            report.put("requestedKinds", kinds);
            report.put(
                    "inventoryQueryScope",
                    "recursive blog namespace, accessStatus=normal, requested provider fileTypes;"
                            + " not a point-in-time snapshot");
            report.put("referenceScope", List.of("music_track", "surprise_video"));
            report.put(
                    "excludedReferenceScopes",
                    List.of(
                            "article_body",
                            "article_cover",
                            "owner_avatar",
                            "database_assets",
                            "external_cover_url"));
            report.put(
                    "newMeaning",
                    "Not referenced by supplied catalog snapshots; NOT proven orphaned or safe to"
                            + " delete");
            report.put("productionImportApproved", false);
            report.put("catalogSnapshotsComplete", complete);
            report.put("warnings", result.warnings());
            report.put(
                    "snapshots",
                    result.snapshots().stream()
                            .map(
                                    s ->
                                            Map.of(
                                                    "source",
                                                    s.source(),
                                                    "path",
                                                    s.path(),
                                                    "available",
                                                    s.available(),
                                                    "sha256",
                                                    s.sha256(),
                                                    "recordCount",
                                                    s.recordCount()))
                            .toList());
            Map<String, Integer> inventoryCounts = new LinkedHashMap<>();
            kinds.forEach(kind -> inventoryCounts.put(kind.name(), preview.count(kind)));
            report.put("inventoryCounts", inventoryCounts);
            report.put(
                    "objects",
                    kinds.stream()
                            .flatMap(kind -> preview.byKind().get(kind).stream())
                            .map(
                                    item ->
                                            Map.of(
                                                    "kind",
                                                    item.kind(),
                                                    "objectKey",
                                                    item.objectKey().value(),
                                                    "metadata",
                                                    item.metadata()))
                            .toList());
            Map<String, Long> summary = new LinkedHashMap<>();
            for (MediaReconciliationStatus status : MediaReconciliationStatus.values())
                summary.put(status.name(), result.reconciliation().count(status));
            report.put("summary", summary);
            report.put("entries", result.reconciliation().entries());
            report.put("resultCode", code);
            output.println(
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .writerWithDefaultPrettyPrinter()
                            .writeValueAsString(report));
            output.flush();
            if (output.checkError()) {
                errors.println(
                        "Media dry-run failed: report output could not be written completely.");
                errors.flush();
                return 1;
            }
            return code;
        } catch (Exception failure) {
            return reportInputFailure(errors);
        }
    }

    public static void validateArguments(String[] args) {
        Options.parse(args);
    }

    public static int reportInputFailure(PrintWriter errors) {
        errors.println(
                "Media dry-run failed: check explicit catalog paths, snapshot format, namespace and"
                        + " ImgBed configuration.");
        errors.flush();
        return 1;
    }

    private record Options(Path music, Path surprise, boolean allowMissingSurprise) {
        static Options parse(String[] args) {
            Path music = null, surprise = null;
            boolean dryRun = false, allowMissing = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--dry-run" -> {
                        if (dryRun) throw new IllegalArgumentException();
                        dryRun = true;
                    }
                    case "--allow-missing-surprise-snapshot" -> {
                        if (allowMissing) throw new IllegalArgumentException();
                        allowMissing = true;
                    }
                    case "--music-catalog" -> {
                        if (music != null || ++i == args.length)
                            throw new IllegalArgumentException();
                        music = Path.of(args[i]);
                    }
                    case "--surprise-catalog" -> {
                        if (surprise != null || ++i == args.length)
                            throw new IllegalArgumentException();
                        surprise = Path.of(args[i]);
                    }
                    default -> throw new IllegalArgumentException();
                }
            }
            if (!dryRun || music == null || surprise == null) throw new IllegalArgumentException();
            return new Options(
                    music.toAbsolutePath().normalize(),
                    surprise.toAbsolutePath().normalize(),
                    allowMissing);
        }
    }
}
