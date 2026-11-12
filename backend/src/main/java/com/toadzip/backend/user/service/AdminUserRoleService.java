package com.toadzip.backend.user.service;

import com.toadzip.backend.admin.domain.AdminDataChange;
import com.toadzip.backend.admin.repository.AdminDataChangeRepository;
import com.toadzip.backend.user.domain.User;
import com.toadzip.backend.user.exception.AdminUserNotFoundException;
import com.toadzip.backend.user.exception.SelfAdminRevokeException;
import com.toadzip.backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 화면에서 카카오·구글 회원의 관리자 권한을 주고 뺀다. 바뀐 경우에만 admin_data_changes에 남긴다.
 * actor는 다른 관리자 작업과 같이 세션 이름(비밀번호 관리자는 로그인 이름, 소셜 관리자는 회원 번호)이다.
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserRoleService {

    private static final String RESOURCE = "USER";

    private final UserRepository users;
    private final AdminDataChangeRepository changes;

    @Transactional
    public void grant(long userId, String actor) {
        User user = lockedUser(userId);
        if (user.isAdmin()) {
            return;
        }
        user.grantAdmin();
        changes.save(new AdminDataChange(RESOURCE, userId, "GRANT_ADMIN", actor,
                "{\"admin\":false}", "{\"admin\":true}"));
    }

    /**
     * 자기 자신의 권한은 뺄 수 없다. 소셜 관리자가 빼면 대상과 자신의 회원 행을 번호 순서로 잠근 뒤
     * 자신이 아직 관리자인지 다시 확인해, 두 관리자가 동시에 서로의 권한을 빼 관리자가 모두 사라지는 일을 막는다.
     */
    @Transactional
    public void revoke(long userId, Authentication actor) {
        User user;
        if (SocialSessions.isSocialSession(actor)) {
            long actorId = Long.parseLong(actor.getName());
            if (actorId == userId) {
                throw new SelfAdminRevokeException();
            }
            User first = lockedUser(Math.min(actorId, userId));
            User second = lockedUser(Math.max(actorId, userId));
            User actorUser = actorId < userId ? first : second;
            if (!actorUser.isAdmin()) {
                throw new AccessDeniedException("관리자 권한이 필요합니다.");
            }
            user = actorId < userId ? second : first;
        } else {
            user = lockedUser(userId);
        }
        if (!user.isAdmin()) {
            return;
        }
        user.revokeAdmin();
        changes.save(new AdminDataChange(RESOURCE, userId, "REVOKE_ADMIN", actor.getName(),
                "{\"admin\":true}", "{\"admin\":false}"));
    }

    private User lockedUser(long userId) {
        return users.findForUpdateById(userId).orElseThrow(AdminUserNotFoundException::new);
    }
}
