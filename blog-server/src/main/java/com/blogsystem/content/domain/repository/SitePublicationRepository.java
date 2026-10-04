package com.blogsystem.content.domain.repository;

import com.blogsystem.content.domain.query.SitePublicationView;

public interface SitePublicationRepository {
    SitePublicationView status();
    SitePublicationView retry();
}
