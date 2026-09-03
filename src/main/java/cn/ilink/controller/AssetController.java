package cn.ilink.controller;

import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.entity.Asset;
import cn.ilink.entity.User;
import cn.ilink.service.AssetLifecycleService;
import cn.ilink.service.FileService;
import cn.ilink.service.impl.AssetServiceImpl;
import cn.ilink.service.UserService;
import cn.ilink.util.UserPreviewHelper;
import cn.ilink.vo.AssetCategoryStatVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import static cn.ilink.common.ControllerUtils.safePage;
import static cn.ilink.common.ControllerUtils.safeSize;

@Controller
@RequestMapping("/api/asset")
public class AssetController {

    private static final Pattern CATEGORY_IN_DESC = Pattern.compile("（分类：([^）]+)）");
    private static final Set<String> ALLOWED_CATEGORIES = Set.of(
        "竞赛获奖", "技术创新", "论文发表", "科研项目", "作品项目", "荣誉称号", "奖学金",
        "学术研究", "艺术创作", "社会实践", "其他");

    @Autowired
    private AssetServiceImpl assetService;

    @Autowired
    private UserService userService;

    @Autowired
    private AssetLifecycleService assetLifecycleService;

    @Autowired
    private FileService fileService;

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

        if (category != null && !category.trim().isEmpty()) {
            wrapper.eq(Asset::getCategory, category.trim());
        }

        int safePage = safePage(page);
        int safeSize = safeSize(size, 100);
        Page<Asset> pageReq = new Page<>(safePage, safeSize);
        Page<Asset> result = assetService.page(pageReq, wrapper);
        List<Map<String, Object>> rows = enrichAssetsWithOwners(result.getRecords());

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("categoryStats", calculateCategoryStats());

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
        m.put("category", resolveCategory(asset));
        m.put("fileUrl", asset.getFileUrl());
        m.put("userId", asset.getUserId());
        m.put("viewCount", asset.getViewCount());
        m.put("downloadCount", asset.getDownloadCount());
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

    private Map<String, Object> calculateCategoryStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        Map<String, Integer> categoryCounts = new LinkedHashMap<>();
        ALLOWED_CATEGORIES.forEach(category -> categoryCounts.put(category, 0));
        long total = 0;
        for (AssetCategoryStatVO row : assetService.getCategoryStats()) {
            if (row == null || row.getCount() == null) continue;
            String category = row.getCategory() == null || row.getCategory().trim().isEmpty()
                ? "其他" : row.getCategory().trim();
            int count = Math.toIntExact(row.getCount());
            categoryCounts.put(category, count);
            total += row.getCount();
        }

        stats.put("total", total);
        stats.putAll(categoryCounts);
        return stats;
    }

    @GetMapping("/{id}")
    @ResponseBody
    public ResponseEntity<Result<?>> getAsset(@PathVariable Long id) {
        Asset asset = assetService.getById(id);
        if (asset != null) {
            assetService.update(new UpdateWrapper<Asset>()
                .eq("id", id)
                .setSql("view_count = IFNULL(view_count, 0) + 1"));
            asset.setViewCount(asset.getViewCount() == null ? 1 : asset.getViewCount() + 1);
            User owner = asset.getUserId() != null ? userService.getById(asset.getUserId()) : null;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", asset.getId());
            data.put("title", asset.getTitle());
            data.put("description", asset.getDescription());
            data.put("category", resolveCategory(asset));
            data.put("fileUrl", asset.getFileUrl());
            data.put("userId", asset.getUserId());
            data.put("viewCount", asset.getViewCount());
            data.put("downloadCount", asset.getDownloadCount());
            data.put("createdAt", asset.getCreatedAt());
            data.put("ownerPreview", UserPreviewHelper.toPreview(owner));
            return Result.ok("获取成功", data).toResponseEntity();
        } else {
            return Result.notFound("成果不存在").toResponseEntity();
        }
    }

    @PostMapping("/upload")
    @ResponseBody
    @CacheEvict(value = "assetDetail", allEntries = true)
    public ResponseEntity<Result<?>> uploadAsset(HttpServletRequest request,
                                                         @RequestParam("title") String title,
                                                         @RequestParam("description") String description,
                                                         @RequestParam(required = false) String category,
                                                         HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        if (title == null || title.trim().isEmpty()) {
            return Result.badRequest("请填写成果名称").toResponseEntity();
        }

        try {
            MultipartFile file = resolveOptionalUploadFile(request);
            String normalizedCategory = normalizeCategory(category, description);
            Asset asset = assetLifecycleService.createAsset(
                user.getId(), title.trim(), stripCategoryFromDescription(description), normalizedCategory, file);
            return Result.ok("发布成功", asset).toResponseEntity();
        } catch (IllegalArgumentException e) {
            return Result.badRequest(e.getMessage()).toResponseEntity();
        } catch (IOException e) {
            return Result.fail(500, "文件上传失败，请稍后重试").toResponseEntity();
        } catch (IllegalStateException e) {
            return Result.fail(500, "保存失败，请稍后重试").toResponseEntity();
        }
    }

    @PutMapping("/{id}")
    @ResponseBody
    @CacheEvict(value = "assetDetail", key = "#id")
    public ResponseEntity<Result<?>> updateAsset(@PathVariable Long id,
                                                        HttpServletRequest request,
                                                        @RequestParam("title") String title,
                                                        @RequestParam("description") String description,
                                                        @RequestParam(required = false) String category,
                                                        HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        if (title == null || title.trim().isEmpty()) {
            return Result.badRequest("请填写成果名称").toResponseEntity();
        }

        try {
            MultipartFile file = resolveOptionalUploadFile(request);
            String normalizedCategory = normalizeCategory(category, description);
            Asset asset = assetLifecycleService.updateOwnedAsset(
                id, user.getId(), title.trim(), stripCategoryFromDescription(description), normalizedCategory, file);
            return Result.ok("保存成功", asset).toResponseEntity();
        } catch (NoSuchElementException e) {
            return Result.notFound(e.getMessage()).toResponseEntity();
        } catch (AccessDeniedException e) {
            return Result.fail(403, e.getMessage()).toResponseEntity();
        } catch (IllegalArgumentException e) {
            return Result.badRequest(e.getMessage()).toResponseEntity();
        } catch (IOException e) {
            return Result.fail(500, "文件上传失败，请稍后重试").toResponseEntity();
        } catch (IllegalStateException e) {
            return Result.fail(500, "保存失败，请稍后重试").toResponseEntity();
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

    @DeleteMapping("/{id}")
    @ResponseBody
    @CacheEvict(value = "assetDetail", key = "#id")
    public ResponseEntity<Result<?>> deleteAsset(@PathVariable Long id, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        try {
            assetLifecycleService.deleteOwnedAsset(id, user.getId());
            return Result.ok("删除成功", null).toResponseEntity();
        } catch (NoSuchElementException e) {
            return Result.notFound(e.getMessage()).toResponseEntity();
        } catch (AccessDeniedException e) {
            return Result.fail(403, e.getMessage()).toResponseEntity();
        } catch (IllegalStateException e) {
            return Result.fail(500, "删除失败，请稍后重试").toResponseEntity();
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

            Path filePath = fileService.resolveStoredPath(asset.getFileUrl());
            if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                byte[] bytes = Files.readAllBytes(filePath);
                assetService.update(new UpdateWrapper<Asset>()
                    .eq("id", id)
                    .setSql("download_count = IFNULL(download_count, 0) + 1"));
                String filename = filePath.getFileName().toString();

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
                headers.setContentDispositionFormData("attachment", filename);
                headers.set("X-Content-Type-Options", "nosniff");

                return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
            }

            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private String resolveCategory(Asset asset) {
        if (asset != null && asset.getCategory() != null && !asset.getCategory().trim().isEmpty()) {
            return asset.getCategory().trim();
        }
        String legacy = asset == null ? "" : extractCategoryFromDescription(asset.getDescription());
        return legacy.isEmpty() ? "其他" : legacy;
    }

    private String normalizeCategory(String category, String description) {
        String value = category == null ? "" : category.trim();
        if (value.isEmpty()) {
            value = extractCategoryFromDescription(description);
        }
        if (value.isEmpty()) {
            value = "其他";
        }
        if (!ALLOWED_CATEGORIES.contains(value)) {
            throw new IllegalArgumentException("成果分类无效");
        }
        return value;
    }
}
