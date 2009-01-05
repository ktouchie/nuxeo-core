package org.nuxeo.ecm.core.convert.cache;

import java.io.File;

import org.nuxeo.common.utils.Path;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.blobholder.SimpleBlobHolder;
import org.nuxeo.ecm.core.api.impl.blob.FileBlob;

public class SimpleCachableBlobHolder extends SimpleBlobHolder implements CachableBlobHolder{

    public SimpleCachableBlobHolder(Blob blob) {
        super(blob);
    }

    public SimpleCachableBlobHolder(String path) {
        super(new FileBlob(new File(path)));
    }

    public void load(String path) {
        this.blob = new FileBlob(new File(path));
    }

    public String persist(String basePath) throws Exception{
        Path path = new Path(basePath);
        path = path.append(getHash());
        File file = new File(path.toString());
        getBlob().transferTo(file);
        return file.getAbsolutePath();
    }


}
