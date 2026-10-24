package cn.ilink.collab;

import cn.ilink.entity.TeamApplication;
import cn.ilink.entity.TeamCollabDocument;
import cn.ilink.entity.TeamCollabDocumentVersion;
import cn.ilink.entity.TeamCollabEditLease;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.User;
import cn.ilink.mapper.TeamApplicationMapper;
import cn.ilink.mapper.TeamCollabDocumentMapper;
import cn.ilink.mapper.TeamCollabDocumentVersionMapper;
import cn.ilink.mapper.TeamCollabEditLeaseMapper;
import cn.ilink.mapper.TeamDemandMapper;
import cn.ilink.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Real-MySQL coverage for M3 lease exclusivity, optimistic locking and idempotent replay.
 * Run with: DB_PASSWORD=... mvn -Dtest=TeamCollaborationMysqlIT -Dilink.mysql.it=true test
 * The dedicated schema ilink_it_m3 is created on demand and rolled back per test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("mysql-it")
@EnabledIfSystemProperty(named = "ilink.mysql.it", matches = "true")
@Transactional
class TeamCollaborationMysqlIT {

    @Autowired
    private TeamDocumentService documentService;
    @Autowired
    private TeamCollaborationLeaseService leaseService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private TeamDemandMapper teamMapper;
    @Autowired
    private TeamApplicationMapper applicationMapper;
    @Autowired
    private TeamCollabDocumentMapper documentMapper;
    @Autowired
    private TeamCollabDocumentVersionMapper versionMapper;
    @Autowired
    private TeamCollabEditLeaseMapper leaseMapper;

    private User leader;
    private User member;
    private TeamDemand team;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        leader = user("leader-" + suffix);
        member = user("member-" + suffix);
        team = new TeamDemand();
        team.setTitle("M3 integration " + suffix);
        team.setDescription("lease and optimistic lock coverage");
        team.setCategory("技术开发");
        team.setRequiredMemberCount(3);
        team.setStatus("TEAMING");
        team.setCreatorId(leader.getId());
        team.setCreatedAt(new Date());
        team.setUpdatedAt(new Date());
        teamMapper.insert(team);

        TeamApplication application = new TeamApplication();
        application.setTeamId(team.getId());
        application.setUserId(member.getId());
        application.setInitiatorId(member.getId());
        application.setStatus("APPROVED");
        application.setMemberRole("STUDENT");
        application.setCreatedAt(new Date());
        application.setReviewedAt(new Date());
        applicationMapper.insert(application);
    }

    @Test
    void leaseIsExclusiveAndExpiredLeaseCanBeTakenOver() {
        TeamCollabDocument document = document("lease");
        TeamCollaborationLeaseService.LeaseView first = leaseService.acquire(
            team.getId(), "DOCUMENT", document.getId(), leader.getId(), 1);

        TeamCollabException held = assertThrows(TeamCollabException.class,
            () -> leaseService.acquire(team.getId(), "DOCUMENT", document.getId(),
                member.getId(), 1));
        assertEquals(TeamCollabException.Code.EDIT_LEASE_HELD, held.code());

        TeamCollabEditLease current = leaseMapper.selectOne(
            new LambdaQueryWrapper<TeamCollabEditLease>()
                .eq(TeamCollabEditLease::getAssetType, "DOCUMENT")
                .eq(TeamCollabEditLease::getAssetId, document.getId()));
        current.setExpiresAt(new Date(System.currentTimeMillis() - 60_000));
        leaseMapper.updateById(current);

        TeamCollaborationLeaseService.LeaseView takenOver = leaseService.acquire(
            team.getId(), "DOCUMENT", document.getId(), member.getId(), 1);
        assertNotNull(takenOver.leaseToken());
        leaseService.release(team.getId(), "DOCUMENT", document.getId(),
            member.getId(), takenOver.leaseToken());

        TeamCollaborationLeaseService.LeaseView reacquired = leaseService.acquire(
            team.getId(), "DOCUMENT", document.getId(), leader.getId(), 1);
        assertNotNull(reacquired.leaseToken());
        assertNotNull(first.leaseToken());
    }

    @Test
    void staleVersionIsRejectedAndReplayDoesNotCreateAnotherSnapshot() {
        TeamDocumentService.DocumentView created = documentService.create(team.getId(),
            leader.getId(), new TeamDocumentService.CreateRequest("方案", "第一版", "TEAM"));
        TeamCollaborationLeaseService.LeaseView lease = leaseService.acquire(
            team.getId(), "DOCUMENT", created.id(), leader.getId(), created.version());
        TeamDocumentService.SaveRequest request = new TeamDocumentService.SaveRequest(
            "方案", "第二版", "TEAM", created.version(), lease.leaseToken(),
            "req-" + UUID.randomUUID(), "MANUAL", "更新正文");

        TeamDocumentService.DocumentView saved = documentService.update(
            team.getId(), created.id(), leader.getId(), request);
        assertEquals(2, saved.version());

        TeamDocumentService.DocumentView replay = documentService.update(
            team.getId(), created.id(), leader.getId(), request);
        assertEquals(2, replay.version());

        TeamDocumentService.SaveRequest stale = new TeamDocumentService.SaveRequest(
            "方案", "第三版", "TEAM", created.version(), lease.leaseToken(),
            "req-" + UUID.randomUUID(), "MANUAL", "过期版本");
        TeamCollabException conflict = assertThrows(TeamCollabException.class,
            () -> documentService.update(team.getId(), created.id(), leader.getId(), stale));
        assertEquals(TeamCollabException.Code.EDIT_VERSION_CONFLICT, conflict.code());

        assertEquals(2, documentMapper.selectById(created.id()).getVersion());
        Long snapshots = versionMapper.selectCount(
            new LambdaQueryWrapper<TeamCollabDocumentVersion>()
                .eq(TeamCollabDocumentVersion::getDocumentId, created.id()));
        assertEquals(2L, snapshots);
    }

    private User user(String username) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("integration-test");
        user.setRole("STUDENT");
        user.setStatus("ACTIVE");
        user.setRealName(username);
        user.setCreatedAt(new Date());
        assertEquals(1, userMapper.insert(user));
        return user;
    }

    private TeamCollabDocument document(String title) {
        TeamCollabDocument document = new TeamCollabDocument();
        document.setTeamId(team.getId());
        document.setTitle(title);
        document.setContentMarkdown("正文");
        document.setVisibility("TEAM");
        document.setStatus("ACTIVE");
        document.setVersion(1);
        document.setContentHash(TeamCollabMarkdown.hash("正文"));
        document.setCreatorUserId(leader.getId());
        document.setLastEditorUserId(leader.getId());
        document.setCreatedAt(new Date());
        document.setUpdatedAt(new Date());
        assertEquals(1, documentMapper.insert(document));
        return document;
    }
}
