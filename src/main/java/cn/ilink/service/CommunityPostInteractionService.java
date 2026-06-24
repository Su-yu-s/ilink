package cn.ilink.service;

import java.util.List;
import java.util.Map;

/**
 * 社区文章点赞、收藏。
 */
public interface CommunityPostInteractionService {

    boolean hasLiked(Long postId, Long userId);

    boolean hasFavorited(Long postId, Long userId);

    /** 批量查询用户对一组帖子的点赞状态 */
    Map<Long, Boolean> batchLikedStatus(Long userId, List<Long> postIds);

    /** 批量查询用户对一组帖子的收藏状态 */
    Map<Long, Boolean> batchFavoritedStatus(Long userId, List<Long> postIds);

    /**
     * @return liked, likeCount；post 不存在返回 null
     */
    Map<String, Object> toggleLike(Long postId, Long userId);

    /**
     * @return favorited, favoriteCount；post 不存在返回 null
     */
    Map<String, Object> toggleFavorite(Long postId, Long userId);
}
