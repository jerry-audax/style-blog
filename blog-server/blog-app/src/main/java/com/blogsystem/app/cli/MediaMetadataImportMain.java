package com.blogsystem.app.cli;

import com.blogsystem.app.config.cli.MediaCliConfiguration;
import com.blogsystem.domain.asset.inventory.model.valobj.MediaImportFailure;
import com.blogsystem.trigger.cli.media.MediaMetadataImportCli;

import java.io.PrintWriter;
import java.util.Map;

/** Minimal offline process; never starts the blog, web server, AI, Redis or scheduled jobs. */
public final class MediaMetadataImportMain {
    public static void main(String[] args) {
        int code =
                execute(
                        args,
                        System.getenv(),
                        new PrintWriter(System.out, true),
                        new PrintWriter(System.err, true));
        if (code != 0) System.exit(code);
    }

    public static int execute(
            String[] args,
            Map<String, String> environment,
            PrintWriter output,
            PrintWriter errors) {
        try {
            var sources = MediaMetadataImportCli.parseSources(args);
            var service = MediaCliConfiguration.importer(environment, sources.database());
            return new MediaMetadataImportCli(service).execute(args, output, errors);
        } catch (MediaImportFailure failure) {
            return MediaMetadataImportCli.reportFailure(failure, errors);
        } catch (Exception failure) {
            return MediaMetadataImportCli.reportFailure(
                    new MediaImportFailure(
                            "INPUT",
                            MediaImportFailure.Outcome.ROLLBACK_ATTEMPTED,
                            failure.getClass().getSimpleName()),
                    errors);
        }
    }
}
