package com.blogsystem.application.content.service;

import com.blogsystem.domain.content.gateway.SitePublicationGateway;
import com.blogsystem.domain.content.query.SitePublicationView;

import org.springframework.stereotype.Service;

@Service
public class SitePublicationApplicationService {
    private final SitePublicationGateway publication;

    public SitePublicationApplicationService(SitePublicationGateway publication) {
        this.publication = publication;
    }

    public SitePublicationView status() {
        return publication.status();
    }

    public SitePublicationView retry() {
        return publication.retry();
    }
}
