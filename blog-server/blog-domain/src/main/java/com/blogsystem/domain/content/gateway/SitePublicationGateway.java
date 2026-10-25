package com.blogsystem.domain.content.gateway;

import com.blogsystem.domain.content.query.SitePublicationView;

public interface SitePublicationGateway {
    SitePublicationView status();

    SitePublicationView retry();
}
