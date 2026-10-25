package com.blogsystem.trigger.http.asset.dto;

import java.util.List;

public final class MediaPublicationMachineRequests {
    private MediaPublicationMachineRequests() {}
    public record Prepare(Integer protocolVersion, String releaseId, String sourceDigest,
            List<String> links, List<String> contents, List<String> htmlContents) {}
    public record Verify(Integer protocolVersion, String manifestDigest, String sourceDigest,
            List<String> links, List<String> contents, List<String> htmlContents) {}
    public record Release(Integer protocolVersion, String manifestDigest, String expectedVersion, Boolean retirementConfirmed) {}
}
