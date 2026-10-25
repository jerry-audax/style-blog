package com.blogsystem.trigger.cli.media;

import com.blogsystem.application.asset.inventory.service.MediaMetadataImportApplicationService;

import com.blogsystem.trigger.cli.media.MediaMetadataImportCli;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MediaMetadataImportCliTest {
    @Test void cliRejectsMissingApplyBeforeOpeningDatabase() {
        var importer = mock(MediaMetadataImportApplicationService.class);
        int code = new MediaMetadataImportCli(importer).execute(new String[0],
                new PrintWriter(new StringWriter()), new PrintWriter(new StringWriter()));
        assertEquals(1, code);
        verifyNoInteractions(importer);
        assertEquals("com.blogsystem.trigger.cli.media", MediaMetadataImportCli.class.getPackageName());
    }
}
