package com.blogsystem.trigger.cli.media;

import com.blogsystem.application.asset.inventory.service.MediaMetadataImportApplicationService;
import com.blogsystem.domain.asset.inventory.model.query.MediaImportReceipt;
import com.blogsystem.domain.asset.inventory.model.valobj.MediaImportFailure;
import com.blogsystem.domain.asset.inventory.model.valobj.MediaImportSources;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Operator transport only. Credentials, SQL, file parsing and transactions are supplied from app. */
public final class MediaMetadataImportCli {
    private final MediaMetadataImportApplicationService importer;
    private final ObjectMapper json = new ObjectMapper();

    public MediaMetadataImportCli(MediaMetadataImportApplicationService importer) {
        this.importer = Objects.requireNonNull(importer);
    }

    public int execute(String[] args, PrintWriter output, PrintWriter errors) {
        boolean committed = false;
        try {
            var sources = parseSources(args);
            String receipt = importer.execute(sources, this::serialize);
            committed = true;
            output.println(receipt);
            output.flush();
            return output.checkError() ? 2 : 0;
        } catch (MediaImportFailure failure) {
            return reportFailure(failure, errors);
        } catch (Exception failure) {
            return reportFailure(
                    new MediaImportFailure(
                            committed ? "RECEIPT" : "INPUT",
                            committed
                                    ? MediaImportFailure.Outcome.COMMITTED
                                    : MediaImportFailure.Outcome.ROLLBACK_ATTEMPTED,
                            failure.getClass().getSimpleName()),
                    errors);
        }
    }

    private String serialize(MediaImportReceipt receipt) {
        try {
            return json.writerWithDefaultPrettyPrinter().writeValueAsString(receipt);
        } catch (JsonProcessingException failure) {
            // The original receipt was prepared in the VIDEO stage, before attempting commit.
            throw new MediaImportFailure(
                    "VIDEO",
                    MediaImportFailure.Outcome.ROLLBACK_ATTEMPTED,
                    failure.getClass().getSimpleName());
        }
    }

    /** Shared with bootstrap so malformed arguments are rejected before constructing a database runtime. */
    public static MediaImportSources parseSources(String[] args) {
        var values = new HashMap<String, String>();
        boolean apply = false;
        var supported =
                Set.of(
                        "--database",
                        "--schema",
                        "--report",
                        "--music-catalog",
                        "--surprise-catalog",
                        "--playlist",
                        "--ddl",
                        "--backup");
        for (int index = 0; index < args.length; index++) {
            if ("--apply".equals(args[index])) {
                if (apply) throw new IllegalArgumentException();
                apply = true;
                continue;
            }
            String key = args[index];
            if (!supported.contains(key)
                    || ++index == args.length
                    || values.putIfAbsent(key, args[index]) != null)
                throw new IllegalArgumentException();
        }
        if (!apply) throw new IllegalArgumentException();
        return new MediaImportSources(
                required(values, "--database"),
                values.getOrDefault("--schema", "public"),
                Path.of(required(values, "--report")),
                Path.of(required(values, "--music-catalog")),
                Path.of(required(values, "--surprise-catalog")),
                optional(values, "--playlist"),
                optional(values, "--ddl"),
                optional(values, "--backup"));
    }

    public static int reportFailure(MediaImportFailure failure, PrintWriter errors) {
        errors.println(
                "Media metadata import outcome="
                        + failure.outcome()
                        + "; verify database before retry. stage="
                        + failure.stage()
                        + " errorType="
                        + failure.errorType());
        errors.flush();
        return failure.outcome() == MediaImportFailure.Outcome.ROLLBACK_ATTEMPTED ? 1 : 2;
    }

    private static String required(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing option");
        return value;
    }

    private static Path optional(Map<String, String> values, String name) {
        return values.containsKey(name) ? Path.of(values.get(name)) : null;
    }
}
