package cn.ilink.service.impl;

import cn.ilink.entity.CommunityPost;
import cn.ilink.entity.CommunityPostFavorite;
import cn.ilink.entity.CommunityPostLike;
import cn.ilink.mapper.CommunityPostFavoriteMapper;
import cn.ilink.mapper.CommunityPostLikeMapper;
import cn.ilink.service.CommunityPostInteractionService;
import cn.ilink.service.CommunityPostService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class CommunityPostInteractionServiceImpl implements CommunityPostInteractionService {

    @Autowired
    private CommunityPostLikeMapper communityPostLikeMapper;

    @Autowired
    private CommunityPostFavoriteMapper communityPostFavoriteMapper;

    @Autowired
    private CommunityPostService communityPostService;

    @Override
    public boolean hasLiked(Long postId, Long userId) {
        if (postId == null || userId == null) {
            return false;
        }
        return communityPostLikeMapper.selectCount(
            new LambdaQueryWrapper<CommunityPostLike>()
                .eq(CommunityPostLike::getPostId, postId)
                .eq(CommunityPostLike::getUserId, userId)
        ) > 0;
    }

    @Override
    public boolean hasFavorited(Long postId, Long userId) {
        if (postId == null || userId == null) {
            return false;
        }
        return communityPostFavoriteMapper.selectCount(
            new LambdaQueryWrapper<CommunityPostFavorite>()
                .eq(CommunityPostFavorite::getPostId, postId)
                .eq(CommunityPostFavorite::getUserId, userId)
        ) > 0;
    }

    @Override
    public Map<Long, Boolean> batchLikedStatus(Long userId, List<Long> postIds) {
        if (userId == null || postIds == null || postIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<CommunityPostLike> rows = communityPostLikeMapper.selectList(
            new LambdaQueryWrapper<CommunityPostLike>()
                .eq(CommunityPostLike::getUserId, userId)
                .in(CommunityPostLike::getPostId, postIds)
        );
        Set<Long> likedIds = rows.stream()
            .map(CommunityPostLike::getPostId)
            .collect(Collectors.toSet());
        return postIds.stream()
            .collect(Collectors.toMap(id -> id, likedIds::contains));
    }

    @Override
    public Map<Long, Boolean> batchFavoritedStatus(Long userId, List<Long> postIds) {
        if (userId == null || postIds == null || postIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<CommunityPostFavorite> rows = communityPostFavoriteMapper.selectList(
            new LambdaQueryWrapper<CommunityPostFavorite>()
                .eq(CommunityPostFavorite::getUserId, userId)
                .in(CommunityPostFavorite::getPostId, postIds)
        );
        Set<Long> favoritedIds = rows.stream()
            .map(CommunityPostFavorite::getPostId)
            .collect(Collectors.toSet());
        return postIds.stream()
            .collect(Collectors.toMap(id -> id, favoritedIds::contains));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> toggleLike(Long postId, Long userId) {
        CommunityPost post = communityPostService.getById(postId);
        if (post == null) {
            return null;
        }
        long exist = communityPostLikeMapper.selectCount(
            new LambdaQueryWrapper<CommunityPostLike>()
                .eq(CommunityPostLike::getPostId, postId)
                .eq(CommunityPostLike::getUserId, userId)
        );
        boolean nowLiked;
        if (exist > 0) {
            communityPostLikeMapper.delete(
                new LambdaQueryWrapper<CommunityPostLike>()
                    .eq(CommunityPostLike::getPostId, postId)
                    .eq(CommunityPostLike::getUserId, userId)
            );
            communityPostService.update(
                new LambdaUpdateWrapper<CommunityPost>()
                    .setSql("like_count = GREATEST(IFNULL(like_count, 0) - 1, 0)")
                    .eq(CommunityPost::getId, postId)
            );
            nowLiked = false;
        } else {
            CommunityPostLike row = new CommunityPostLike();
            row.setPostId(postId);
            row.setUserId(userId);
            row.setCreatedAt(new Date());
            communityPostLikeMapper.insert(row);
            communityPostService.update(
                new LambdaUpdateWrapper<CommunityPost>()
                    .setSql("like_count = IFNULL(like_count, 0) + 1")
                    .eq(CommunityPost::getId, postId)
            );
            nowLiked = true;
        }
        post = communityPostService.getById(postId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("liked", nowLiked);
        m.put("likeCount", likeCountOf(post));
        return m;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> toggleFavorite(Long postId, Long userId) {
        CommunityPost post = communityPostService.getById(postId);
        if (post == null) {
            return null;
        }
        long exist = communityPostFavoriteMapper.selectCount(
            new LambdaQueryWrapper<CommunityPostFavorite>()
                .eq(CommunityPostFavorite::getPostId, postId)
                .eq(CommunityPostFavorite::getUserId, userId)
        );
        boolean nowFavorited;
        if (exist > 0) {
            communityPostFavoriteMapper.delete(
                new LambdaQueryWrapper<CommunityPostFavorite>()
                    .eq(CommunityPostFavorite::getPostId, postId)
                    .eq(CommunityPostFavorite::getUserId, userId)
            );
            communityPostService.update(
                new LambdaUpdateWrapper<CommunityPost>()
                    .setSql("favorite_count = GREATEST(IFNULL(favorite_count, 0) - 1, 0)")
                    .eq(CommunityPost::getId, postId)
            );
            nowFavorited = false;
        } else {
            CommunityPostFavorite row = new CommunityPostFavorite();
            row.setPostId(postId);
            row.setUserId(userId);
            row.setCreatedAt(new Date());
            communityPostFavoriteMapper.insert(row);
            communityPostService.update(
                new LambdaUpdateWrapper<CommunityPost>()
                    .setSql("favorite_count = IFNULL(favorite_count, 0) + 1")
                    .eq(CommunityPost::getId, postId)
            );
            nowFavorited = true;
        }
        post = communityPostService.getById(postId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("favorited", nowFavorited);
        m.put("favoriteCount", favoriteCountOf(post));
        return m;
    }

    private static int likeCountOf(CommunityPost p) {
        return p.getLikeCount() == null ? 0 : p.getLikeCount();
    }

    private static int favoriteCountOf(CommunityPost p) {
        return p.getFavoriteCount() == null ? 0 : p.getFavoriteCount();
    }
}
