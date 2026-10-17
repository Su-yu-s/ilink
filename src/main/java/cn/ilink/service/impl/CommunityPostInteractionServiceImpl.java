package cn.ilink.service.impl;

import cn.ilink.entity.CommunityPost;
import cn.ilink.entity.CommunityPostFavorite;
import cn.ilink.entity.CommunityPostLike;
import cn.ilink.entity.User;
import cn.ilink.mapper.CommunityPostFavoriteMapper;
import cn.ilink.mapper.CommunityPostLikeMapper;
import cn.ilink.service.CommunityPostInteractionService;
import cn.ilink.service.impl.CommunityPostServiceImpl;
import cn.ilink.service.NotificationService;
import cn.ilink.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import cn.ilink.util.DisplayNameResolver;

@Service
public class CommunityPostInteractionServiceImpl implements CommunityPostInteractionService {

    @Autowired
    private CommunityPostLikeMapper communityPostLikeMapper;

    @Autowired
    private CommunityPostFavoriteMapper communityPostFavoriteMapper;

    @Autowired
    private CommunityPostServiceImpl communityPostService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private UserService userService;

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
        // 修复 P2-2：事务首句对帖子行加排他锁（SELECT ... FOR UPDATE），把同一帖子的
        // 点赞/取消串行化。此前 DELETE/INSERT community_post_like（唯一键记录锁与 gap 锁）
        // 与 UPDATE community_post 行锁在并发事务中以不同顺序获取，InnoDB 检测到循环等待
        // 即回滚败者（DeadlockLoserDataAccessException → 500）。统一以帖子行锁为第一把锁后，
        // 同帖的 like 表操作不再交错；不同帖子锁不同行，互不阻塞。
        CommunityPost post = communityPostService.getOne(
            new LambdaQueryWrapper<CommunityPost>()
                .eq(CommunityPost::getId, postId)
                .last("FOR UPDATE"));
        if (post == null) {
            return null;
        }
        // 修复 D-P2-1（压测 LT-5）：原先 selectCount 判断后 delete 不看影响行数就扣减，
        // 并发同用户 unlike 会导致 like_count 双扣漂移，且长持锁窗口触发 InnoDB 死锁。
        // 改为 delete 优先、按影响行数决定分支，计数增减均为原子 SQL，回滚时一并撤销。
        long removed = communityPostLikeMapper.delete(
            new LambdaQueryWrapper<CommunityPostLike>()
                .eq(CommunityPostLike::getPostId, postId)
                .eq(CommunityPostLike::getUserId, userId)
        );
        boolean nowLiked;
        if (removed > 0) {
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
            try {
                if (communityPostLikeMapper.insert(row) <= 0) {
                    // 插入未生效：不加计数（修复前 insert 失败仍 +1，同样造成计数漂移）
                    post = communityPostService.getById(postId);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("liked", false);
                    m.put("likeCount", likeCountOf(post));
                    return m;
                }
            } catch (DuplicateKeyException e) {
                // 唯一索引兜底：并发重复插入时，视为已点赞，不重复计数
                post = communityPostService.getById(postId);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("liked", true);
                m.put("likeCount", likeCountOf(post));
                return m;
            }
            communityPostService.update(
                new LambdaUpdateWrapper<CommunityPost>()
                    .setSql("like_count = IFNULL(like_count, 0) + 1")
                    .eq(CommunityPost::getId, postId)
            );
            // 通知文章作者（排除作者自己点赞）
            if (!post.getAuthorId().equals(userId)) {
                User actor = userService.getById(userId);
                notificationService.create(
                    post.getAuthorId(),
                    userId,
                    "LIKE",
                    "你的文章被点赞了",
                    displayName(actor) + " 点赞了你的文章《" + post.getTitle() + "》",
                    postId
                );
            }
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
        // 修复 P2-2：与 toggleLike 同理，收藏/取消收藏同样以帖子行排他锁串行化，
        // 消除 community_post_favorite 表与 community_post 行锁的交叉等待。
        CommunityPost post = communityPostService.getOne(
            new LambdaQueryWrapper<CommunityPost>()
                .eq(CommunityPost::getId, postId)
                .last("FOR UPDATE"));
        if (post == null) {
            return null;
        }
        // 修复 D-P2-1：同 toggleLike，delete 优先、按影响行数决定分支
        long removed = communityPostFavoriteMapper.delete(
            new LambdaQueryWrapper<CommunityPostFavorite>()
                .eq(CommunityPostFavorite::getPostId, postId)
                .eq(CommunityPostFavorite::getUserId, userId)
        );
        boolean nowFavorited;
        if (removed > 0) {
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
            try {
                if (communityPostFavoriteMapper.insert(row) <= 0) {
                    // 插入未生效：不加计数
                    post = communityPostService.getById(postId);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("favorited", false);
                    m.put("favoriteCount", favoriteCountOf(post));
                    return m;
                }
            } catch (DuplicateKeyException e) {
                // 唯一索引兜底：并发重复插入时，视为已收藏
                post = communityPostService.getById(postId);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("favorited", true);
                m.put("favoriteCount", favoriteCountOf(post));
                return m;
            }
            communityPostService.update(
                new LambdaUpdateWrapper<CommunityPost>()
                    .setSql("favorite_count = IFNULL(favorite_count, 0) + 1")
                    .eq(CommunityPost::getId, postId)
            );
            // 通知文章作者（排除作者自己收藏）
            if (!post.getAuthorId().equals(userId)) {
                User actor = userService.getById(userId);
                notificationService.create(
                    post.getAuthorId(),
                    userId,
                    "FAVORITE",
                    "你的文章被收藏了",
                    displayName(actor) + " 收藏了你的文章《" + post.getTitle() + "》",
                    postId
                );
            }
            nowFavorited = true;
        }
        post = communityPostService.getById(postId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("favorited", nowFavorited);
        m.put("favoriteCount", favoriteCountOf(post));
        return m;
    }

    private String displayName(User user) {
        if (user == null) return "某位用户";
        // 显示名统一走解析器：不得回退到 username（可能是手机号/学号）
        return DisplayNameResolver.resolve(user);
    }

    private static int likeCountOf(CommunityPost p) {
        return p.getLikeCount() == null ? 0 : p.getLikeCount();
    }

    private static int favoriteCountOf(CommunityPost p) {
        return p.getFavoriteCount() == null ? 0 : p.getFavoriteCount();
    }
}
