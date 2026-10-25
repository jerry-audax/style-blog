package com.blogsystem.app.it.application.asset.upload.service;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadContent;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadProbe;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadReceipt;

import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation.Intent;

import java.util.ArrayList;
import java.util.List;
import com.blogsystem.domain.asset.upload.gateway.MediaUploadStorageGateway;
import java.util.function.Consumer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
/** Deterministic external boundary only, never registered in production. */
public final class FakeMediaUploadStorageRepository implements MediaUploadStorageGateway {
    public final List<String> calls=new ArrayList<>();
    public final List<Integer> chunkLengths=new ArrayList<>();
    public Consumer<String> before=ignored->{};
    public MediaUploadProbe probeResult;
    public String session="fake_session";
    private void call(String name){if(TransactionSynchronizationManager.isActualTransactionActive())throw new AssertionError("Provider called inside transaction");calls.add(name);before.accept(name);}
    public MediaUploadReceipt uploadDirect(MediaUploadOperation.Intent intent,MediaUploadContent source){call("direct");return receipt(intent);}
    public String beginChunks(MediaUploadOperation.Intent intent){call("init");return session;}
    public void uploadChunk(MediaUploadOperation.Intent intent,String id,int index,byte[] bytes){if(!session.equals(id))throw new AssertionError("Wrong session");call("chunk:"+index);chunkLengths.add(bytes.length);}
    public MediaUploadReceipt mergeChunks(MediaUploadOperation.Intent intent,String id){if(!session.equals(id))throw new AssertionError("Wrong session");call("merge");return receipt(intent);}
    public MediaUploadProbe probe(MediaUploadOperation.Intent intent){call("probe");return probeResult==null?new MediaUploadProbe(true,List.of()):probeResult;}
    public static MediaUploadReceipt receipt(MediaUploadOperation.Intent intent){return new MediaUploadReceipt(new MediaObjectKey(intent.operationFolder().value()+"/"+intent.candidateAssetId()+".bin"));}
}
