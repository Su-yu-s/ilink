package cn.ilink.service.kg;

import cn.ilink.mapper.KgDocumentChunkMapper;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.mapper.KgRelationMapper;
import org.springframework.stereotype.Component;

/** Creates an isolated evidence ledger for each B3 question. */
@Component
public class EvidenceLedgerFactory {
    private final KgEntityMapper entityMapper;
    private final KgRelationMapper relationMapper;
    private final KgDocumentChunkMapper chunkMapper;
    private final KgScopeService scopeService;

    public EvidenceLedgerFactory(KgEntityMapper entityMapper, KgRelationMapper relationMapper,
                                 KgDocumentChunkMapper chunkMapper, KgScopeService scopeService) {
        this.entityMapper = entityMapper;
        this.relationMapper = relationMapper;
        this.chunkMapper = chunkMapper;
        this.scopeService = scopeService;
    }

    public EvidenceLedger create() {
        return new EvidenceLedger(entityMapper, relationMapper, chunkMapper, scopeService);
    }
}
