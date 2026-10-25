package com.blogsystem.domain.asset.inventory.repository;

import com.blogsystem.domain.asset.inventory.model.query.MediaImportBatch;
import com.blogsystem.domain.asset.inventory.model.valobj.MediaImportSources;

public interface MediaImportInputRepository {
    MediaImportBatch read(MediaImportSources sources);
}
