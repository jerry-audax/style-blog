package com.blogsystem.domain.asset.publication.model.gateway;

@FunctionalInterface
public interface PublicationMachineCredentialVerifier {
    boolean accepts(String credential);
}
