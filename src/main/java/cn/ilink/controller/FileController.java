package cn.ilink.controller;

import cn.ilink.common.Result;
import cn.ilink.service.FileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private static final Logger log = LoggerFactory.getLogger(FileController.class);

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping("/upload")
    public ResponseEntity<Result<?>> upload(@RequestParam("file") MultipartFile file,
                                                 @RequestParam("bizType") String bizType) {
        try {
            String url = fileService.upload(file, bizType);
            return Result.ok("\u4e0a\u4f20\u6210\u529f", url).toResponseEntity();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Result.badRequest(e.getMessage()));
        } catch (IOException e) {
            log.error("\u6587\u4ef6\u4e0a\u4f20\u5931\u8d25", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(500, "\u6587\u4ef6\u4e0a\u4f20\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5"));
        }
    }
}
