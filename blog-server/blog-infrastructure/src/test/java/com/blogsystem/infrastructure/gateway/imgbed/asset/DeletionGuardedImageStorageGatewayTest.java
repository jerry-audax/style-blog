package com.blogsystem.infrastructure.gateway.imgbed.asset;

import com.blogsystem.domain.asset.image.gateway.ImageStorageGateway;
import com.blogsystem.domain.asset.image.model.valobj.*;
import com.blogsystem.domain.asset.image.model.query.ImagePage;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DeletionGuardedImageStorageGatewayTest {
    static class Provider implements ImageStorageGateway {
        int deletes;ImagePage page=new ImagePage(List.of(),0);
        public StoredImage upload(ImageUpload image,long owner){return new StoredImage("blog/image.png","https://example.test/image.png","image/png",4);}
        public ImagePage list(int offset,int count,String search){return page;}
        public void delete(ImagePath path){deletes++;}
    }
    @Test void postgresStopsInternalDeleteBeforeProvider(){var provider=new Provider();var gateway=new DeletionGuardedImageStorageGateway(provider,false);assertEquals(ImageStorageException.Reason.OPERATION_NOT_READY,assertThrows(ImageStorageException.class,()->gateway.delete(new ImagePath("blog/image.png"))).reason());assertEquals(0,provider.deletes);}
    @Test void explicitLegacyReadAndUploadAreUnchanged(){var provider=new Provider();var gateway=new DeletionGuardedImageStorageGateway(provider,true);assertSame(provider.page,gateway.list(0,20,""));assertEquals("blog/image.png",gateway.upload(null,1).path());}
    @Test void explicitLegacyStillDelegatesDelete(){var provider=new Provider();new DeletionGuardedImageStorageGateway(provider,true).delete(new ImagePath("blog/image.png"));assertEquals(1,provider.deletes);}
}
