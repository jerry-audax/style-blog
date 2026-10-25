package com.blogsystem.app.cli;

import com.blogsystem.app.config.cli.MediaCliConfiguration;
import com.blogsystem.trigger.cli.media.MediaInventoryDryRunCli;

import java.io.PrintWriter;
import java.util.Map;

public final class MediaInventoryDryRunMain {
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
            MediaInventoryDryRunCli.validateArguments(args);
            var runtime = MediaCliConfiguration.dryRun(environment);
            return new MediaInventoryDryRunCli(
                            runtime.service(),
                            runtime.provider(),
                            runtime.endpoint(),
                            runtime.root())
                    .execute(args, output, errors);
        } catch (Exception failure) {
            return MediaInventoryDryRunCli.reportInputFailure(errors);
        }
    }
}
