package cn.ilink.controller;

import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.entity.Asset;
import cn.ilink.entity.User;
import cn.ilink.service.AssetService;
import cn.ilink.service.UserService;
import cn.ilink.util.UserPreviewHelper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/api/asset")
public class AssetController {

    private static final Pattern CATEGORY_IN_DESC = Pattern.compile("（分类：([^）]+)）");

    @Autowired
    private AssetService assetService;

    @Autowired
    private UserService userService;

    @Value("${upload.path:uploads}")
    private String uploadPath;

    @GetMapping("/list")
    @ResponseBody
    public ResponseEntity<Result<?>> listAssets(@RequestParam(defaultValue = "1") Integer page,
                                                        @RequestParam(defaultValue = "10") Integer size,
                                                        @RequestParam(required = false) String keyword,
                                                        @RequestParam(required = false) String category,
                                                        @RequestParam(required = false, defaultValue = "latest") String sort) {

        LambdaQueryWrapper<Asset> wrapper = new LambdaQueryWrapper<>();
        if ("popular".equalsIgnoreCase(sort)) {
            wrapper.orderByDesc(Asset::getViewCount).orderByDesc(Asset::getCreatedAt);
        } else {
            wrapper.orderByDesc(Asset::getCreatedAt);
        }

        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(Asset::getTitle, kw).or().like(Asset::getDescription, kw));
        }

        // 当前 asset 表暂无分类字段，前端传 category 时用标题/描述兜底匹配
        if (category != null && !category.trim().isEmpty()) {
            String cat = category.trim();
            wrapper.and(w -> w.like(Asset::getTitle, cat).or().like(Asset::getDescription, cat));
        }

        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Page<Asset> pageReq = new Page<>(safePage, safeSize);
        Page<Asset> result = assetService.page(pageReq, wrapper);
        List<Map<String, Object>> rows = enrichAssetsWithOwners(result.getRecords());

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("categoryStats", calculateCategoryStats(assetService.list()));

        return ResponseEntity.ok(
            Result.ok("获取成功", rows).withPagination(safePage, safeSize, result.getTotal()).withExtra(extra)
        );
    }

    private List<Map<String, Object>> enrichAssetsWithOwners(List<Asset> assets) {
        if (assets == null || assets.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> userIds = assets.stream()
            .map(Asset::getUserId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, User> owners = loadOwnersByIds(userIds);
        return assets.stream()
            .map(a -> assetToListMap(a, owners.get(a.getUserId())))
            .collect(Collectors.toList());
    }

    /** 批量查发布者；listByIds 未命中时逐条 getById，保证列表 ownerPreview 与详情一致 */
    private Map<Long, User> loadOwnersByIds(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, User> owners = userService.listByIds(new ArrayList<>(userIds)).stream()
            .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
        for (Long id : userIds) {
            if (id != null && !owners.containsKey(id)) {
                User u = userService.getById(id);
                if (u != null) {
                    owners.put(id, u);
                }
            }
        }
        return owners;
    }

    private Map<String, Object> assetToListMap(Asset asset, User owner) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", asset.getId());
        m.put("title", asset.getTitle());
        m.put("description", asset.getDescription());
        m.put("category", extractCategoryFromDescription(asset.getDescription()));
        m.put("fileUrl", asset.getFileUrl());
        m.put("userId", asset.getUserId());
        m.put("viewCount", asset.getViewCount());
        m.put("createdAt", asset.getCreatedAt());
        m.put("ownerPreview", UserPreviewHelper.toPreview(owner));
        return m;
    }

    static String extractCategoryFromDescription(String description) {
        if (description == null) {
            return "";
        }
        Matcher matcher = CATEGORY_IN_DESC.matcher(description);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    static String stripCategoryFromDescription(String description) {
        if (description == null) {
            return "";
        }
        return CATEGORY_IN_DESC.matcher(description).replaceAll("").trim();
    }

    static String appendCategoryToDescription(String body, String category) {
        String base = body != null ? body.trim() : "";
        if (category == null || category.trim().isEmpty()) {
            return base;
        }
        if (base.contains("（分类：")) {
            return base;
        }
        return base + "（分类：" + category.trim() + "）";
    }

    private Map<String, Object> calculateCategoryStats(List<Asset> allAssets) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", allAssets.size());
        
        Map<String, Integer> categoryCounts = new LinkedHashMap<>();
        categoryCounts.put("技术创新", 0);
        categoryCounts.put("学术研究", 0);
        categoryCounts.put("艺术创作", 0);
        categoryCounts.put("社会实践", 0);

        for (Asset asset : allAssets) {
            String description = asset.getDescription();
            if (description != null) {
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("（分类：([^）]+)）").matcher(description);
                if (matcher.find()) {
                    String cat = matcher.group(1).trim();
                    categoryCounts.merge(cat, 1, Integer::sum);
                }
            }
        }

        stats.putAll(categoryCounts);
        return stats;
    }

    @GetMapping("/{id}")
    @ResponseBody
    @Cacheable(value = "assetDetail", key = "#id")
    public ResponseEntity<Result<?>> getAsset(@PathVariable Long id) {
        Asset asset = assetService.getById(id);
        if (asset != null) {
            User owner = asset.getUserId() != null ? userService.getById(asset.getUserId()) : null;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", asset.getId());
            data.put("title", asset.getTitle());
            data.put("description", asset.getDescription());
            data.put("category", extractCategoryFromDescription(asset.getDescription()));
            data.put("fileUrl", asset.getFileUrl());
            data.put("userId", asset.getUserId());
            data.put("viewCount", asset.getViewCount());
            data.put("createdAt", asset.getCreatedAt());
            data.put("ownerPreview", UserPreviewHelper.toPreview(owner));
            return ResponseEntity.ok(Result.ok("获取成功", data));
        } else {
            return ResponseEntity.ok(Result.notFound("成果不存在"));
        }
    }

    @PostMapping("/upload")
    @ResponseBody
    @CacheEvict(value = "assetDetail", allEntries = true)
    public ResponseEntity<Result<?>> uploadAsset(HttpServletRequest request,
                                                         @RequestParam("title") String title,
                                                         @RequestParam("description") String description,
                                                         HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }
        if (title == null || title.trim().isEmpty()) {
            return ResponseEntity.ok(Result.badRequest("请填写成果名称"));
        }

        try {
            MultipartFile file = resolveOptionalUploadFile(request);
            String fileUrl = null;
            if (file != null) {
                fileUrl = storeUploadedFile(file);
                if (fileUrl == null) {
                    return ResponseEntity.ok(Result.fail(500, "文件上传失败，请稍后重试"));
                }
            }

            Asset asset = new Asset();
            asset.setTitle(title.trim());
            asset.setDescription(description != null ? description.trim() : "");
            asset.setFileUrl(fileUrl);
            asset.setUserId(user.getId());
            asset.setViewCount(0);

            if (assetService.save(asset)) {
                return ResponseEntity.ok(Result.ok("发布成功", asset));
            }
            return ResponseEntity.ok(Result.fail(500, "保存失败"));
        } catch (IOException e) {
            return ResponseEntity.ok(Result.fail(500, "文件上传失败，请稍后重试"));
        }
    }

    @PutMapping("/{id}")
    @ResponseBody
    @CacheEvict(value = "assetDetail", key = "#id")
    public ResponseEntity<Result<?>> updateAsset(@PathVariable Long id,
                                                        HttpServletRequest request,
                                                        @RequestParam("title") String title,
                                                        @RequestParam("description") String description,
                                                        HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }
        Asset asset = assetService.getById(id);
        if (asset == null) {
            return ResponseEntity.ok(Result.notFound("成果不存在"));
        }
        if (asset.getUserId() == null || !asset.getUserId().equals(user.getId())) {
            return ResponseEntity.ok(Result.fail(403, "无权编辑该成果"));
        }
        if (title == null || title.trim().isEmpty()) {
            return ResponseEntity.ok(Result.badRequest("请填写成果名称"));
        }

        try {
            MultipartFile file = resolveOptionalUploadFile(request);
            if (file != null) {
                deleteStoredFile(asset.getFileUrl());
                String fileUrl = storeUploadedFile(file);
                if (fileUrl == null) {
                    return ResponseEntity.ok(Result.fail(500, "文件上传失败，请稍后重试"));
                }
                asset.setFileUrl(fileUrl);
            }
            asset.setTitle(title.trim());
            asset.setDescription(description != null ? description.trim() : "");
            if (assetService.updateById(asset)) {
                return ResponseEntity.ok(Result.ok("保存成功", asset));
            }
            return ResponseEntity.ok(Result.fail(500, "保存失败"));
        } catch (IOException e) {
            return ResponseEntity.ok(Result.fail(500, "文件上传失败，请稍后重试"));
        }
    }

    /** 附件选填：未上传 part 时不抛 MissingServletRequestPartException */
    private MultipartFile resolveOptionalUploadFile(HttpServletRequest request) {
        if (!(request instanceof MultipartHttpServletRequest)) {
            return null;
        }
        MultipartFile file = ((MultipartHttpServletRequest) request).getFile("file");
        if (file == null || file.isEmpty()) {
            return null;
        }
        return file;
    }

    private String storeUploadedFile(MultipartFile file) throws IOException {
        File uploadDir = new File(uploadPath);
        if (!uploadDir.exists()) {
            uploadDir.mkdirs();
        }
        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String filename = UUID.randomUUID().toString() + extension;
        Path filePath = Paths.get(uploadPath, filename);
        Files.write(filePath, file.getBytes());
        return "/uploads/" + filename;
    }

    private void deleteStoredFile(String fileUrl) {
        if (fileUrl == null || !fileUrl.startsWith("/uploads/")) {
            return;
        }
        try {
            String filename = fileUrl.substring("/uploads/".length());
            Files.deleteIfExists(Paths.get(uploadPath, filename));
        } catch (IOException ignored) {
            // 忽略删除失败
        }
    }

    @GetMapping("/download/{id}")
    @ResponseBody
    public ResponseEntity<byte[]> downloadAsset(@PathVariable Long id, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("请先登录".getBytes());
        }
        try {
            Asset asset = assetService.getById(id);
            if (asset == null) {
                return ResponseEntity.notFound().build();
            }

            // 增加下载次数（安全拆箱，避免 NPE）
            asset.setViewCount(asset.getViewCount() != null ? asset.getViewCount() + 1 : 1);
            assetService.updateById(asset);

            // 读取文件
            String fileUrl = asset.getFileUrl();
            if (fileUrl.startsWith("/uploads/")) {
                String filename = fileUrl.substring("/uploads/".length());
                Path filePath = Paths.get(uploadPath, filename);

                if (Files.exists(filePath)) {
                    byte[] bytes = Files.readAllBytes(filePath);

                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
                    headers.setContentDispositionFormData("attachment", filename);

                    return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
                }
            }

            return ResponseEntity.notFound().build();
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
