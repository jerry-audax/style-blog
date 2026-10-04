package com.blogsystem.surprise.domain.repository;

import com.blogsystem.surprise.domain.model.SurpriseVideoMedia;
import com.blogsystem.surprise.domain.model.SurpriseVideoUpload;

public interface SurpriseVideoMediaRepository {
    SurpriseVideoMedia upload(SurpriseVideoUpload upload);
    void delete(String path);
}
