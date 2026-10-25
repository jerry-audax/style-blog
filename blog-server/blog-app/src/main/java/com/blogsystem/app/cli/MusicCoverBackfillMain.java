package com.blogsystem.app.cli;

import com.blogsystem.app.config.cli.MusicCoverBackfillRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;

/** Operator explicitly opts into provider writes; credentials only come from the environment. */
public final class MusicCoverBackfillMain {
    public static void main(String[] args) {
        if(args.length!=1 || !Arrays.asList(args).contains("--apply-local")) {
            System.err.println("Explicit --apply-local is required"); System.exit(2); return;
        }
        try(var runtime=new MusicCoverBackfillRuntime(System.getenv())) {
            long actor=Long.parseLong(System.getenv("MUSIC_COVER_OWNER_ID"));
            int limit=Integer.parseInt(System.getenv().getOrDefault("MUSIC_COVER_BATCH_LIMIT","2000"));
            var result=runtime.batch().run(actor,limit);
            System.out.println("MUSIC_COVER_RECEIPT="+new ObjectMapper().writeValueAsString(result));
        } catch(Exception failure) {
            System.err.println("Cover batch not confirmed: "+failure.getClass().getSimpleName());
            System.exit(1);
        }
    }
}
