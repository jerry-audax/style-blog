package com.blogsystem.content.application;

import com.blogsystem.content.domain.query.SitePublicationView;
import com.blogsystem.content.domain.repository.SitePublicationRepository;
import org.springframework.stereotype.Service;

@Service
public class SitePublicationApplicationService {
    private final SitePublicationRepository publication;
    public SitePublicationApplicationService(SitePublicationRepository publication) {this.publication = publication;}
    public SitePublicationView status() {return publication.status();}
    public SitePublicationView retry() {return publication.retry();}
}
