package cn.ilink.mapper;

import cn.ilink.entity.TeamAgentRun;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

@Mapper
public interface TeamAgentRunMapper extends BaseMapper<TeamAgentRun> {
    @Select("SELECT * FROM team_agent_run WHERE id = #{id} FOR UPDATE")
    TeamAgentRun selectByIdForUpdate(@Param("id") Long id);

    @Update("UPDATE team_agent_run "
        + "SET status = 'RUNNING', worker_id = #{workerId}, worker_lease_token = #{leaseToken}, "
        + "lease_until = #{leaseUntil}, started_at = COALESCE(started_at, #{now}), "
        + "resolved_route = #{route}, version = version + 1 "
        + "WHERE id = #{runId} AND status = 'QUEUED'")
    int claimQueued(@Param("runId") Long runId,
                    @Param("workerId") String workerId,
                    @Param("leaseToken") String leaseToken,
                    @Param("leaseUntil") Date leaseUntil,
                    @Param("route") String route,
                    @Param("now") Date now);

    @Update("UPDATE team_agent_run SET lease_until = #{leaseUntil}, version = version + 1 "
        + "WHERE id = #{runId} AND status = 'RUNNING' "
        + "AND worker_lease_token = #{leaseToken} AND lease_until > #{now} "
        + "AND cancel_requested_at IS NULL")
    int renewLease(@Param("runId") Long runId,
                   @Param("leaseToken") String leaseToken,
                   @Param("leaseUntil") Date leaseUntil,
                   @Param("now") Date now);

    @Update("UPDATE team_agent_run SET event_seq = event_seq + 1, version = version + 1 "
        + "WHERE id = #{runId} AND status = 'RUNNING' "
        + "AND worker_lease_token = #{leaseToken} AND lease_until > #{now} "
        + "AND cancel_requested_at IS NULL")
    int incrementEventSeq(@Param("runId") Long runId,
                          @Param("leaseToken") String leaseToken,
                          @Param("now") Date now);
}
