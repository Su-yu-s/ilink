package cn.ilink.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Record-level bridge from a global source to the team's bound catalog competition. */
@Mapper
public interface TeamAgentSourceAuthorizationMapper {
    @Select("SELECT COUNT(*) FROM kg_doc_entity source "
        + "JOIN kg_doc_entity competition ON competition.entity_id = source.entity_id "
        + "WHERE source.doc_type = #{docType} AND source.doc_id = #{docId} "
        + "AND competition.doc_type = 'COMPETITION' "
        + "AND competition.doc_id = #{competitionId}")
    long countCompetitionLinkedSource(@Param("docType") String docType,
                                      @Param("docId") Long docId,
                                      @Param("competitionId") Long competitionId);
}
