package cn.ilink.dto;

/**
 * A safe, user-facing description of a successfully stored upload.
 * The public URL points to the generated storage name, while originalName
 * keeps the sanitized name selected by the user for later display.
 */
public class UploadedFileInfo {

    private final String url;
    private final String originalName;
    private final long size;
    private final String contentType;

    public UploadedFileInfo(String url, String originalName, long size, String contentType) {
        this.url = url;
        this.originalName = originalName;
        this.size = size;
        this.contentType = contentType;
    }

    public String getUrl() {
        return url;
    }

    public String getOriginalName() {
        return originalName;
    }

    public long getSize() {
        return size;
    }

    public String getContentType() {
        return contentType;
    }
}
