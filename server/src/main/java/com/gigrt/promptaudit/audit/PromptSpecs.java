package com.gigrt.promptaudit.audit;

import org.springframework.data.jpa.domain.Specification;

import javax.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;

/** Builds a JPA {@link Specification} from a {@link PromptQuery}. Absent filters are skipped. */
final class PromptSpecs {

    private PromptSpecs() {}

    static Specification<PromptRecord> from(PromptQuery q) {
        return (root, cq, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            // Tenant isolation — an org admin only ever sees their own tenant's rows.
            if (notBlank(q.tenantOrgId)) ps.add(cb.equal(root.get("tenantOrgId"), q.tenantOrgId.trim()));
            if (q.from != null)      ps.add(cb.greaterThanOrEqualTo(root.get("receivedAt"), q.from));
            if (q.to != null)        ps.add(cb.lessThanOrEqualTo(root.get("receivedAt"), q.to));
            if (notBlank(q.userEmail)) ps.add(cb.equal(root.get("userEmail"), q.userEmail.trim()));
            if (notBlank(q.orgId))     ps.add(cb.equal(root.get("orgId"), q.orgId.trim()));
            if (notBlank(q.userUid))   ps.add(cb.equal(root.get("userUid"), q.userUid.trim()));
            if (notBlank(q.repo))      ps.add(cb.equal(root.get("repo"), q.repo.trim()));
            if (notBlank(q.sessionId)) ps.add(cb.equal(root.get("sessionId"), q.sessionId.trim()));
            if (notBlank(q.keyword)) {
                String like = "%" + q.keyword.trim().toLowerCase() + "%";
                ps.add(cb.like(cb.lower(root.get("prompt")), like));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    private static boolean notBlank(String s) { return s != null && !s.trim().isEmpty(); }
}
