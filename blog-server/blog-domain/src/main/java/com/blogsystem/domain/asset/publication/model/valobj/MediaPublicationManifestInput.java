package com.blogsystem.domain.asset.publication.model.valobj;

import com.blogsystem.domain.asset.publication.model.MediaPublicationPinException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public record MediaPublicationManifestInput(String sourceDigest, List<String> links,
        List<String> contents, List<String> htmlContents) {
    public MediaPublicationManifestInput {
        if (sourceDigest == null || !sourceDigest.matches("[0-9a-f]{64}")
                || links == null || contents == null || htmlContents == null) throw invalid();
        if ((long) links.size() + contents.size() + htmlContents.size() > 10000) throw limited();
        links = checked(links); contents = checked(contents); htmlContents = checked(htmlContents);
    }

    private static List<String> checked(List<String> values) {
        for (String value : values) {
            if (value == null) throw invalid();
            // 先按字符数拒绝必然超限的输入，避免为巨大字符串额外分配完整UTF8数组。
            if (value.length() > 1048576 || value.getBytes(StandardCharsets.UTF_8).length > 1048576) throw limited();
        }
        return List.copyOf(values);
    }

    private static MediaPublicationPinException invalid() {
        return new MediaPublicationPinException(MediaPublicationPinException.Reason.INVALID_REQUEST);
    }

    private static MediaPublicationPinException limited() {
        return new MediaPublicationPinException(MediaPublicationPinException.Reason.LIMIT_EXCEEDED);
    }
}
