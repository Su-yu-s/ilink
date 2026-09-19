package cn.ilink.mapper;

import cn.ilink.entity.CommunityComment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface CommunityCommentMapper extends BaseMapper<CommunityComment> {

    /** 按帖子批量统计评论数（group by，避免列表逐帖 count 的 N+1） */
    @Select("<script>SELECT post_id AS postId, COUNT(*) AS cnt FROM community_comment "
        + "WHERE post_id IN <foreach collection='postIds' item='pid' open='(' separator=',' close=')'>#{pid}</foreach> "
        + "GROUP BY post_id</script>")
    List<Map<String, Object>> countByPostIds(@Param("postIds") Collection<Long> postIds);
}
