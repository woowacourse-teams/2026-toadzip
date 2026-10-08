package com.toadzip.backend.streetview.service;

import com.toadzip.backend.admin.domain.AdminDataChange;
import com.toadzip.backend.admin.dto.AdminChangeResponse;
import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.admin.repository.AdminDataChangeRepository;
import com.toadzip.backend.streetview.domain.StreetViewPolicy;
import com.toadzip.backend.streetview.dto.StreetViewPolicyResponse;
import com.toadzip.backend.streetview.dto.StreetViewPolicyUpdateRequest;
import com.toadzip.backend.streetview.exception.InvalidStreetViewRequestException;
import com.toadzip.backend.streetview.exception.StreetViewPolicyUnavailableException;
import com.toadzip.backend.streetview.repository.StreetViewPolicyRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
@PreAuthorize("hasRole('ADMIN')")
public class StreetViewPolicyService {
    private static final String RESOURCE_TYPE = "STREET_VIEW_POLICY";
    private final StreetViewPolicyRepository policies;
    private final AdminDataChangeRepository changes;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public StreetViewPolicyService(StreetViewPolicyRepository policies, AdminDataChangeRepository changes,
            ObjectMapper objectMapper, Clock clock) {
        this.policies = policies;
        this.changes = changes;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public StreetViewPolicyResponse get() {
        return StreetViewPolicyResponse.from(policies.findById(StreetViewPolicy.POLICY_ID)
                .orElseThrow(StreetViewPolicyUnavailableException::new));
    }

    @Transactional
    public StreetViewPolicyResponse update(StreetViewPolicyUpdateRequest request, String actor) {
        var policy = policies.findByIdForUpdate(StreetViewPolicy.POLICY_ID)
                .orElseThrow(StreetViewPolicyUnavailableException::new);
        if (policy.getVersion() != request.version()) {
            throw new AdminDataConflictException("거리뷰 정책이 변경되었습니다. 새로 조회한 뒤 다시 수정해 주세요.");
        }
        String before = objectMapper.writeValueAsString(StreetViewPolicyResponse.from(policy));
        if (policy.revise(request.enabled(), request.reason(), actor, clock.instant())) {
            policies.flush();
            changes.save(new AdminDataChange(RESOURCE_TYPE, StreetViewPolicy.POLICY_ID, "UPDATE", actor,
                    before, objectMapper.writeValueAsString(StreetViewPolicyResponse.from(policy))));
        }
        return StreetViewPolicyResponse.from(policy);
    }

    public List<AdminChangeResponse> history(int page) {
        if (page < 0) {
            throw new InvalidStreetViewRequestException("page", "페이지는 0 이상이어야 합니다.");
        }
        return changes.findByResourceTypeAndResourceIdOrderByIdDesc(RESOURCE_TYPE, StreetViewPolicy.POLICY_ID,
                PageRequest.of(page, 20)).stream().map(AdminChangeResponse::from).toList();
    }
}
