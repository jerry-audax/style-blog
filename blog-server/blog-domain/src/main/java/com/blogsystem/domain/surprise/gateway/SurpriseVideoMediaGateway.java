package com.blogsystem.domain.surprise.gateway;

import com.blogsystem.domain.surprise.model.SurpriseVideoMedia;
import com.blogsystem.domain.surprise.model.SurpriseVideoUpload;

public interface SurpriseVideoMediaGateway {
    SurpriseVideoMedia upload(SurpriseVideoUpload upload);

    void delete(String path);
}
