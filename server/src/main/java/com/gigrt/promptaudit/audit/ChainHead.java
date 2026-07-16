package com.gigrt.promptaudit.audit;

import javax.persistence.*;

/**
 * The head of one tamper-evident chain (spec 0001). One row per chain_key (tenant_org_id, or
 * "__global__" for the tenantless bootstrap chain). Locked FOR UPDATE during ingest to serialize
 * appends per chain so concurrent reports can't fork the chain.
 */
@Entity
@Table(name = "chain_head")
public class ChainHead {

    @Id
    @Column(name = "chain_key", length = 48)
    private String chainKey;

    @Column(name = "head_hash", length = 64, nullable = false)
    private String headHash;

    @Column(name = "seq", nullable = false)
    private long seq;

    public ChainHead() {}

    public ChainHead(String chainKey, String headHash, long seq) {
        this.chainKey = chainKey; this.headHash = headHash; this.seq = seq;
    }

    public String getChainKey() { return chainKey; }
    public void setChainKey(String chainKey) { this.chainKey = chainKey; }
    public String getHeadHash() { return headHash; }
    public void setHeadHash(String headHash) { this.headHash = headHash; }
    public long getSeq() { return seq; }
    public void setSeq(long seq) { this.seq = seq; }
}
